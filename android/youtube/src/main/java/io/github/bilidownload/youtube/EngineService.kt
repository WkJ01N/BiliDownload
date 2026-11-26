package io.github.bilidownload.youtube

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.yausername.youtubedl_android.YoutubeDL
import io.github.bilidownload.bridge.IYoutubeEngine
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class EngineService : Service() {
    private val executor = Executors.newFixedThreadPool(3)
    private val requests = ConcurrentHashMap<String, Running>()
    private val transfers = ConcurrentHashMap<String, Running>()
    private val runtime by lazy { prepareRuntime() }

    private class Running {
        val stopped = AtomicBoolean(false)
        var cleanOnExit = false
        @Volatile var process: Process? = null
        fun cancel() { stopped.set(true); process?.destroyForcibly() }
    }
    private data class Runtime(val script: File, val zip: File, val pythonHome: File)
    private fun prepareRuntime(): Runtime {
        YoutubeDL.init(applicationContext)
        val build = packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        val dir = File(codeCacheDir, "engine-$build").apply { mkdirs() }
        fun unpack(name: String, open: () -> java.io.InputStream): File {
            val target = File(dir, name)
            if (!target.isFile) {
                val temp = File(dir, name + ".tmp")
                open().use { input -> temp.outputStream().use(input::copyTo) }
                check(temp.renameTo(target))
            }
            return target
        }
        unpack("xpost.py") { assets.open("xpost.py") }
        return Runtime(unpack("bridge.py") { assets.open("bridge.py") },
            unpack("yt-dlp") { resources.openRawResource(R.raw.ytdlp) },
            File(noBackupFilesDir, "youtubedl-android/packages/python/usr"))
    }
    private fun taskDir(id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(cacheDir, "tracks/$id")
    }
    private fun checkCaller() {
        val caller = Binder.getCallingUid()
        if (caller != packageManager.getApplicationInfo("io.github.bilidownload", 0).uid ||
            packageManager.checkSignatures(caller, applicationInfo.uid) != android.content.pm.PackageManager.SIGNATURE_MATCH)
            throw SecurityException("Unexpected engine client")
    }
    private val service = object : IYoutubeEngine.Stub() {
        override fun version(): Int { checkCaller(); return 1 }

        override fun run(requestId: String, taskId: String?, json: String, owner: IBinder): ParcelFileDescriptor {
            checkCaller()
            require(UUID.fromString(requestId).toString() == requestId && json.length <= 256 * 1024)
            val fetch = JSONObject(json).getString("op") == "fetch"
            require(fetch == (taskId != null))
            val dir = taskId?.let(::taskDir) ?: cacheDir
            val running = Running()
            check(requests.putIfAbsent(requestId, running) == null)
            if (taskId != null && synchronized(transfers) { transfers.putIfAbsent(taskId, running) } != null) {
                requests.remove(requestId); throw IllegalStateException("Task is still running")
            }
            val pipe = ParcelFileDescriptor.createPipe()
            val death = IBinder.DeathRecipient { running.cancel() }
            executor.execute {
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                        try {
                            owner.linkToDeath(death, 0)
                            val engine = runtime
                            val bin = File(applicationInfo.nativeLibraryDir)
                            dir.mkdirs()
                            val builder = ProcessBuilder(File(bin, "libpython.so").path, "-B", "-s", "-X", "utf8",
                                engine.script.path, engine.zip.path, File(bin, "libqjs.so").path).directory(dir)
                            builder.environment().apply {
                                put("PYTHONHOME", engine.pythonHome.path)
                                put("LD_LIBRARY_PATH", File(engine.pythonHome, "lib").path)
                                put("SSL_CERT_FILE", File(engine.pythonHome, "etc/tls/cert.pem").path)
                                put("TMPDIR", cacheDir.path)
                                put("HOME", noBackupFilesDir.path)
                            }
                            builder.redirectError(ProcessBuilder.Redirect.to(File("/dev/null")))
                            if (running.stopped.get()) return@use
                            val process = builder.start()
                            running.process = process
                            if (running.stopped.get()) { running.cancel(); return@use }
                            process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(json); it.newLine() }
                            process.inputStream.use { it.copyTo(output) }
                            process.waitFor()
                        } catch (_: Exception) {
                            if (!running.stopped.get()) output.write("{\"error\":\"Engine\"}\n".toByteArray())
                        }
                    }
                } finally {
                    running.cancel()
                    runCatching { owner.unlinkToDeath(death, 0) }
                    requests.remove(requestId, running)
                    if (taskId != null) synchronized(transfers) {
                        if (running.cleanOnExit) taskDir(taskId).deleteRecursively()
                        transfers.remove(taskId, running)
                    }
                }
            }
            return pipe[0]
        }

        override fun cancel(requestId: String) { checkCaller(); requests[requestId]?.cancel() }
        override fun openTrack(taskId: String, audio: Boolean): ParcelFileDescriptor {
            checkCaller()
            val dir = taskDir(taskId)
            val track = if (audio) File(dir, "audio.track")
                else File(dir, "x.track").takeIf(File::isFile) ?: File(dir, "video.track")
            return ParcelFileDescriptor.open(track, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun clean(taskId: String) {
            checkCaller()
            val dir = taskDir(taskId)
            synchronized(transfers) {
                val active = transfers[taskId]
                if (active == null) dir.deleteRecursively() else active.cleanOnExit = true
            }
        }
    }
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val stopWhenIdle = object : Runnable {
        override fun run() {
            if (requests.isEmpty()) stopSelf() else mainHandler.postDelayed(this, 30_000)
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        mainHandler.removeCallbacks(stopWhenIdle); mainHandler.postDelayed(stopWhenIdle, 60_000)
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent): IBinder = service
    override fun onDestroy() {
        mainHandler.removeCallbacks(stopWhenIdle); requests.values.forEach { it.cancel() }; executor.shutdown(); super.onDestroy()
    }
}
