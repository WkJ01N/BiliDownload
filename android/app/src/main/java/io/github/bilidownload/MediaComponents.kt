package io.github.bilidownload

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

data class ComponentState(
    val ready: Boolean = false,
    val downloading: Boolean = false,
    val bytes: Long = 0,
    val total: Long? = null,
    val error: String? = null
)

internal class ComponentIntegrityException : IOException()

internal class VerifiedComponentArchive(private val size: Long, private val sha256: String) {
    private fun digest(input: InputStream, onChunk: (ByteArray, Int, Long) -> Unit): Boolean {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var read = 0L
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            read += count
            if (read > size) return false
            hash.update(buffer, 0, count)
            onChunk(buffer, count, read)
        }
        return read == size && hash.digest().joinToString("") { "%02x".format(it) } == sha256
    }

    fun verified(file: File): Boolean = file.isFile && file.length() == size &&
        runCatching { file.inputStream().use { digest(it) { _, _, _ -> } } }.getOrDefault(false)

    fun install(input: InputStream, destination: File, progress: (Long) -> Unit) {
        val directory = checkNotNull(destination.parentFile)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Unable to create the component directory")
        val part = File.createTempFile("media-", ".part", directory)
        try {
            part.outputStream().use { out ->
                if (!digest(input) { buffer, count, read ->
                    out.write(buffer, 0, count)
                    progress(read)
                }) throw ComponentIntegrityException()
            }
            Files.move(part.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            part.delete()
        }
    }
}

class MediaComponents(context: Context) {
    private val manifest = context.assets.open("engine/media-component.json").bufferedReader()
        .use { JSONObject(it.readText()) }
    val downloadBytes = manifest.getLong("bytes")
    private val sha256 = manifest.getString("sha256")
    private val url = URI.create(manifest.getString("url"))
    private val verifier = VerifiedComponentArchive(downloadBytes, sha256)
    private val archive = File(context.noBackupFilesDir, "components/media/$sha256/yt-dlp")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(ComponentState())
    val state = mutable.asStateFlow()
    @Volatile private var verified = false
    private var download: Job? = null

    init {
        scope.launch {
            if (verifier.verified(archive)) synchronized(this@MediaComponents) {
                verified = true
                if (!mutable.value.downloading) mutable.value = ComponentState(ready = true)
            }
        }
    }

    fun requireArchive(x: Boolean = false): File {
        if (archive.isFile && archive.length() == downloadBytes && (verified || verifier.verified(archive))) {
            synchronized(this) {
                verified = true
                if (!mutable.value.ready && !mutable.value.downloading) mutable.value = ComponentState(ready = true)
            }
            return archive
        }
        synchronized(this) {
            verified = false
            if (mutable.value.ready && !mutable.value.downloading) mutable.value = ComponentState()
        }
        throw BiliException("请先下载媒体解析组件。", if (x) BiliErrorKind.XComponent else BiliErrorKind.YoutubeComponent)
    }

    @Synchronized fun startInstall() {
        if (download != null || (mutable.value.ready && archive.isFile)) return
        mutable.value = ComponentState(downloading = true, total = downloadBytes)
        val job = scope.launch {
            var result = ComponentState()
            try {
                ensureActive()
                if (!verifier.verified(archive)) {
                    archive.parentFile?.listFiles { file -> file.name.startsWith("media-") && file.extension == "part" }
                        ?.forEach { it.delete() }
                    openDownload().use { stream ->
                        verifier.install(stream, archive) { read ->
                            ensureActive()
                            mutable.value = ComponentState(downloading = true, bytes = read, total = downloadBytes)
                        }
                    }
                }
                verified = true
                result = ComponentState(ready = true)
            } catch (_: CancellationException) {
                result = ComponentState(error = "已取消下载。")
            } catch (_: ComponentIntegrityException) {
                result = ComponentState(error = "组件校验失败，请重试。")
            } catch (_: Exception) {
                result = ComponentState(error = "组件下载失败。请检查网络和可用存储空间后重试。")
            } finally {
                synchronized(this@MediaComponents) {
                    mutable.value = result
                    download = null
                }
            }
        }
        download = job
        job.invokeOnCompletion {
            synchronized(this) {
                if (download === job) {
                    mutable.value = ComponentState(error = "已取消下载。")
                    download = null
                }
            }
        }
    }

    @Synchronized fun cancelInstall() { download?.cancel() }

    private fun openDownload(): InputStream {
        var location = url
        val allowedHosts = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
        repeat(5) {
            if (location.scheme != "https" || location.host?.lowercase() !in allowedHosts ||
                location.userInfo != null || location.port !in setOf(-1, 443)) throw IOException("Unexpected component host")
            val connection = location.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            try {
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        if (connection.contentLengthLong >= 0 && connection.contentLengthLong != downloadBytes)
                            throw ComponentIntegrityException()
                        val input = connection.inputStream
                        return object : InputStream() {
                            override fun read() = input.read()
                            override fun read(buffer: ByteArray, offset: Int, length: Int) = input.read(buffer, offset, length)
                            override fun close() { try { input.close() } finally { connection.disconnect() } }
                        }
                    }
                    301, 302, 303, 307, 308 -> location = location.resolve(
                        connection.getHeaderField("Location") ?: throw IOException("Missing redirect location")
                    )
                    else -> throw IOException("Component server rejected the request")
                }
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
            connection.disconnect()
        }
        throw IOException("Too many component redirects")
    }
}
