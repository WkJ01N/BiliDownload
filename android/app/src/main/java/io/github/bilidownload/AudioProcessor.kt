package io.github.bilidownload

import android.content.*
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.kyant.taglib.Picture
import com.kyant.taglib.TagLib
import io.github.bilidownload.bridge.IAudioProcessor
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class AudioProcessor(private val context: Context) {
    companion object {
        const val PACKAGE = "io.github.bilidownload.audio"
        const val APK = "https://github.com/WkJ01N/BiliDownload/releases/download/v0.3.0/BiliDownload-0.3.0-audio.apk"
    }
    @Volatile private var activated: IAudioProcessor? = null
    fun activate(binder: IBinder?) { activated = binder?.let { IAudioProcessor.Stub.asInterface(it) } }
    fun installed() = context.packageManager.checkSignatures(PACKAGE, context.packageName) == PackageManager.SIGNATURE_MATCH
    fun requireInstalled() { if(!installed()) throw BiliException("请安装同签名的音频处理扩展。", BiliErrorKind.AudioComponent) }

    suspend fun convert(files: List<File>, out: File, selection: AudioOutput) = withContext(Dispatchers.IO) {
        requireInstalled()
        val ready = CompletableDeferred<IAudioProcessor>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { ready.complete(IAudioProcessor.Stub.asInterface(binder)) }
            override fun onServiceDisconnected(name: ComponentName) { ready.completeExceptionally(BiliException("音频处理扩展已断开，请重试。")) }
            override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
        }
        val bound = context.bindService(Intent().setComponent(ComponentName(PACKAGE,"$PACKAGE.AudioService")).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES),conn,Context.BIND_AUTO_CREATE)
        try {
            val remote = (if(bound) withTimeoutOrNull(8000) { ready.await() } else null) ?: activated?.takeIf { it.asBinder().isBinderAlive }
                ?: throw BiliException("请打开音频处理扩展后重试。",BiliErrorKind.AudioConnect)
            if(remote.version() != 1) throw BiliException("请更新音频处理扩展。",BiliErrorKind.AudioComponent)
            val inputs = mutableListOf<ParcelFileDescriptor>()
            val id = UUID.randomUUID().toString()
            try {
                files.forEach { inputs += ParcelFileDescriptor.open(it,ParcelFileDescriptor.MODE_READ_ONLY) }
                ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE).use { dest ->
                    val cancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { runCatching { remote.cancel(id) } } }
                    try {
                        val result = remote.convert(id,inputs.toTypedArray(),dest,selection.extension,selection.mp3Kbps,selection.track.channels,Binder())
                        ensureActive()
                        if(result != 0 || out.length() == 0L) throw BiliException("音频转换失败，请检查源文件后重试。")
                    } finally { cancellation.cancel() }
                }
            } finally { inputs.forEach { it.close() } }
        } finally { if(bound) context.unbindService(conn) }
    }
}

object AudioTags {
    suspend fun write(file: File, tags: MusicTags) = withContext(Dispatchers.IO) {
        val url = tags.cover.replaceFirst("http://","https://")
        val cover = if(url.startsWith("https://")) try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 4000; conn.readTimeout = 4000
                conn.inputStream.use { input ->
                    val bytes = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while(true) {
                        ensureActive(); val count = input.read(buffer); if(count < 0) break
                        if(bytes.size() + count > 6 * 1024 * 1024) return@use null
                        bytes.write(buffer,0,count)
                    }
                    bytes.toByteArray()
                }
            } finally { conn.disconnect() }
        } catch(e: CancellationException) { throw e } catch(_: Exception) { null } else null
        ensureActive()
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use { fd ->
            val props = TagLib.getMetadata(fd.dup().detachFd(),false)?.propertyMap ?: hashMapOf()
            if(tags.title.isNotBlank()) props["TITLE"] = arrayOf(tags.title)
            if(tags.artist.isNotBlank()) props["ARTIST"] = arrayOf(tags.artist)
            if(tags.album.isNotBlank()) props["ALBUM"] = arrayOf(tags.album)
            if(!TagLib.savePropertyMap(fd.dup().detachFd(),props)) throw BiliException("无法写入音频标签。")
            if(cover != null && cover.size > 8) {
                val type = when { cover[0] == 0xff.toByte() && cover[1] == 0xd8.toByte() -> "image/jpeg"
                    cover[0] == 0x89.toByte() && cover[1] == 0x50.toByte() -> "image/png"; else -> null }
                if(type != null) TagLib.savePictures(fd.dup().detachFd(), arrayOf(Picture(cover,"","Front Cover",type)))
            }
        }
    }
}
