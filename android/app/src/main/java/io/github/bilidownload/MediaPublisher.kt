package io.github.bilidownload

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** Copy with bounded memory and allow cancellation between large I/O requests. */
internal suspend fun copyMediaFile(file: File, output: OutputStream) {
    val coroutine = currentCoroutineContext()
    file.inputStream().use { input ->
        val buffer = ByteArray(512 * 1024)
        while (true) {
            coroutine.ensureActive()
            val size = input.read(buffer)
            if (size < 0) break
            output.write(buffer, 0, size)
        }
    }
}

internal class MediaPublisher(private val context: Context) {
    private data class Reservation(val uri: Uri, val pending: Boolean, val key: Pair<String, String>)
    private val resolver = context.contentResolver
    private val reservations = mutableSetOf<Pair<String, String>>()

    fun selectedTree(): String? = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("directory", null)

    suspend fun publish(file: File, requested: String, mime: String, destination: XDestination?, x: XContent?,
                        tree: String? = if (destination != null) destination.tree else selectedTree()): Uri =
        write(reserve(requested, mime, destination, x, tree)) { uri ->
            resolver.openOutputStream(uri, "w")?.use { copyMediaFile(file, it) }
                ?: throw IOException("无法写入目录")
        }

    private suspend fun write(slot: Reservation, writer: suspend (Uri) -> Unit): Uri {
        try {
            writer(slot.uri)
            currentCoroutineContext().ensureActive()
            if (slot.pending) {
                val published = resolver.update(slot.uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                if (published < 1) throw IOException("无法保存媒体")
            }
            return slot.uri
        } catch (e: Exception) {
            runCatching {
                if (slot.pending) resolver.delete(slot.uri, null, null)
                else DocumentFile.fromSingleUri(context, slot.uri)?.delete()
            }
            throw e
        } finally {
            synchronized(reservations) { reservations.remove(slot.key) }
        }
    }

    private fun reserve(requested: String, mime: String, destination: XDestination?, x: XContent?, tree: String?): Reservation =
        synchronized(reservations) {
            val folder = when {
                mime.startsWith("audio/") -> Environment.DIRECTORY_MUSIC
                mime.startsWith("video/") -> Environment.DIRECTORY_MOVIES
                mime.startsWith("image/") -> Environment.DIRECTORY_PICTURES
                else -> Environment.DIRECTORY_DOWNLOADS
            }
            val postFolder = if (destination?.grouped == true && x != null) Links.safeName(x.handle + "-" + x.postId) else null
            val collection = when {
                postFolder != null || folder == Environment.DIRECTORY_DOWNLOADS -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
                folder == Environment.DIRECTORY_MUSIC -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                folder == Environment.DIRECTORY_PICTURES -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                else -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            val directory = if (postFolder == null) "$folder/BiliDownload" else "${Environment.DIRECTORY_DOWNLOADS}/BiliDownload/$postFolder"
            val parent = tree?.let {
                val root = DocumentFile.fromTreeUri(context, Uri.parse(it)) ?: throw IOException("目录不可用")
                if (destination == null) root else {
                    val child = postFolder ?: folder
                    (root.findFile(child) ?: root.createDirectory(child))?.takeIf { it.isDirectory }
                        ?: throw IOException("无法创建子目录")
                }
            }
            val location = parent?.uri?.toString() ?: directory
            val occupied = reservations.filter { it.first == location }.mapTo(mutableSetOf()) { it.second }
            if (parent != null) parent.listFiles().mapNotNullTo(occupied) { it.name }
            else resolver.query(collection, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf("$directory/"), null)?.use { cursor ->
                while (cursor.moveToNext()) occupied += cursor.getString(0)
            }
            var suffix = 0
            var name = requested
            while (name in occupied) name = FileNaming.candidate(requested, ++suffix)
            val uri = if (parent != null) parent.createFile(mime, name)?.uri else
                resolver.insert(collection, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, directory)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                })
            if (uri == null) throw IOException("无法创建输出文件")
            val key = location to name
            reservations += key
            Reservation(uri, parent == null, key)
        }
}
