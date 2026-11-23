package io.github.bilidownload.audio

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import io.github.bilidownload.bridge.IAudioProcessor
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Local file descriptors are granted by the signed host for one conversion. */
class AudioService : Service() {
    private class Work {
        @Volatile var stopped = false
        @Volatile var process: Process? = null
        fun stop() { stopped = true; process?.destroyForcibly() }
    }
    private val active = ConcurrentHashMap<String, Work>()
    private fun caller() {
        val uid = Binder.getCallingUid()
        check(uid == packageManager.getApplicationInfo("io.github.bilidownload", 0).uid &&
            packageManager.checkSignatures(uid, applicationInfo.uid) == PackageManager.SIGNATURE_MATCH)
    }
    private val service = object : IAudioProcessor.Stub() {
        override fun version(): Int { caller(); return 1 }
        override fun cancel(id: String) { caller(); active[id]?.stop() }
        override fun convert(id: String, sources: Array<out ParcelFileDescriptor>, destination: ParcelFileDescriptor,
                             extension: String, mp3Kbps: Int, channels: Int, owner: IBinder): Int {
            caller()
            val muxer = mapOf(".flac" to "flac", ".m4a" to "mp4", ".opus" to "opus", ".mp3" to "mp3")[extension] ?: return 1
            if(UUID.fromString(id).toString() != id || sources.isEmpty() || mp3Kbps !in listOf(0,128,192,320) || mp3Kbps != 0 && extension != ".mp3") return 1
            val work = Work()
            if(active.putIfAbsent(id, work) != null) return 1
            val dir = File(cacheDir, id)
            val death = IBinder.DeathRecipient { work.stop() }
            try {
                owner.linkToDeath(death,0)
                check(dir.mkdir())
                fun copy(input: java.io.InputStream, output: java.io.OutputStream) {
                    val buffer = ByteArray(65536)
                    while(!work.stopped) { val n = input.read(buffer); if(n < 0) return; output.write(buffer,0,n) }
                    throw InterruptedException()
                }
                val files = sources.mapIndexed { i, fd ->
                    File(dir, "part-$i").also { part -> ParcelFileDescriptor.AutoCloseInputStream(fd).use { input -> part.outputStream().use { copy(input,it) } } }
                }
                val args = mutableListOf(File(applicationInfo.nativeLibraryDir,"libaudio-ffmpeg.so").path,"-nostdin","-hide_banner","-loglevel","error","-y","-protocol_whitelist","file")
                if(files.size == 1) args += listOf("-i",files.first().name)
                else {
                    File(dir,"segments.txt").writeText(files.joinToString("\n") { "file '${it.name}'" })
                    args += listOf("-f","concat","-safe","1","-i","segments.txt")
                }
                args += listOf("-map","0:a:0","-vn","-c:a", if(mp3Kbps == 0) "copy" else "libmp3lame")
                if(mp3Kbps > 0) args += listOf("-b:a","${mp3Kbps}k","-ac",if(channels == 1) "1" else "2")
                val out = File(dir,"audio$extension")
                args += listOf("-f",muxer,out.name)
                val proc = ProcessBuilder(args).directory(dir).redirectErrorStream(true).redirectOutput(File("/dev/null")).start()
                work.process = proc
                if(work.stopped) work.stop()
                if(proc.waitFor() != 0 || out.length() == 0L) return 2
                ParcelFileDescriptor.AutoCloseOutputStream(destination).use { output -> out.inputStream().use { copy(it,output) } }
                return 0
            } catch (_: Exception) { return 2 }
            finally {
                work.stop(); active.remove(id,work); runCatching { owner.unlinkToDeath(death,0) }
                sources.forEach { runCatching { it.close() } }; runCatching { destination.close() }; dir.deleteRecursively()
            }
        }
    }
    override fun onBind(intent: Intent): IBinder = service
    override fun onDestroy() { active.values.forEach { it.stop() }; super.onDestroy() }
}
