package io.github.bilidownload

import android.content.Context
import android.os.ParcelFileDescriptor
import com.kyant.taglib.Picture
import com.kyant.taglib.TagLib
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.TimeUnit

class AudioProcessor(private val context: Context) {
    private val executable = File(context.applicationInfo.nativeLibraryDir, "libaudio-ffmpeg.so")

    fun requireInstalled() {
        if (!executable.isFile) throw BiliException("当前安装包缺少媒体转换组件，请重新安装 BiliDownload。")
    }

    fun requireGif() = requireInstalled()

    suspend fun convert(files: List<File>, out: File, selection: AudioOutput) {
        val muxer = when (selection.extension) {
            ".m4a" -> "mp4"
            ".mp3" -> "mp3"
            ".opus" -> "opus"
            ".flac" -> "flac"
            else -> throw BiliException("不支持此音频输出格式。")
        }
        val convert = selection.mp3Kbps > 0
        val args = mutableListOf("-map", "0:a:0", "-vn", "-c:a", if (convert) "libmp3lame" else "copy")
        if (convert) args += listOf("-b:a", "${selection.mp3Kbps}k", "-ac", if (selection.track.channels == 1) "1" else "2")
        process(files, out, muxer, args)
    }

    suspend fun convertGif(source: File, output: File) {
        process(listOf(source), output, "gif", listOf("-an", "-sn", "-dn", "-filter_complex",
            "[0:v:0]fps=15,scale='min(480,iw)':-2:flags=lanczos,split[frames][colors];[colors]palettegen[palette];[frames][palette]paletteuse",
            "-loop", "0"))
    }

    suspend fun muxVideo(video: File, audio: File, output: File) {
        process(listOf(video), output, "mp4", listOf("-map", "0:v:0", "-map", "1:a:0", "-c", "copy", "-sn", "-dn"), audio)
    }

    private suspend fun process(files: List<File>, out: File, muxer: String, options: List<String>,
                                audio: File? = null) = withContext(Dispatchers.IO) {
        requireInstalled()
        require(files.isNotEmpty())
        val work = Files.createTempDirectory(context.cacheDir.toPath(), "convert-").toFile()
        try {
            val command = mutableListOf(executable.path, "-nostdin", "-hide_banner", "-loglevel", "error",
                "-y", "-protocol_whitelist", "file")
            if (files.size == 1) {
                command += listOf("-i", files.single().absolutePath)
            } else {
                val playlist = File(work, "segments.txt")
                playlist.writeText(files.joinToString("\n") { file ->
                    val path = file.absolutePath
                    require('\n' !in path && '\r' !in path)
                    "file '${path.replace("'", "'\\''")}'"
                })
                command += listOf("-f", "concat", "-safe", "0", "-i", playlist.absolutePath)
            }
            if (audio != null) command += listOf("-protocol_whitelist", "file", "-i", audio.absolutePath)
            val staged = File(work, "converted")
            command += options
            command += listOf("-f", muxer, staged.absolutePath)
            ensureActive()
            val process = try {
                ProcessBuilder(command).directory(work).redirectErrorStream(true)
                    .redirectOutput(File("/dev/null")).start()
            } catch (_: IOException) {
                throw BiliException("无法启动媒体转换组件。")
            }
            try {
                while (!process.waitFor(200, TimeUnit.MILLISECONDS)) ensureActive()
                ensureActive()
                if (process.exitValue() != 0 || staged.length() == 0L)
                    throw BiliException("格式转换失败，请检查源文件后重试。")
                Files.move(staged.toPath(), out.toPath(), REPLACE_EXISTING)
            } finally {
                if (process.isAlive) {
                    process.destroyForcibly()
                    process.waitFor(2, TimeUnit.SECONDS)
                }
            }
        } finally {
            work.deleteRecursively()
        }
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
