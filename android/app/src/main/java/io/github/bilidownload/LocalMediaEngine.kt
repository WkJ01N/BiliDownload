package io.github.bilidownload

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipFile

internal class LocalMediaEngine(
    private val context: Context,
    private val requireArchive: (Boolean) -> File = { BiliApplication.components.requireArchive(it) },
    private val testDiagnostics: ((String) -> Unit)? = null
) {
    private val slots = Semaphore(3)
    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    private val ytDlpCacheDir = File(context.cacheDir, "yt-dlp-cache")
    private val workerMutex = Mutex()
    private val workerLock = Any()
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var workerProcess: Process? = null
    private var workerArchive: File? = null
    private var workerWriter: BufferedWriter? = null
    private var workerReader: BufferedReader? = null
    private var idleJob: Job? = null

    fun taskDirectory(id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(context.cacheDir, "downloads/$id")
    }

    companion object {
        private val runtimeLock = Any()
        private val generatedRuntimeName = Regex("\\d+-\\d+|preparing")

        private fun currentRuntime(context: Context): File {
            val payload = File(context.applicationInfo.nativeLibraryDir, "libpython.zip.so")
            val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            return File(context.noBackupFilesDir, "media-engine/$version-${payload.length()}")
        }

        fun pruneObsoleteRuntimes(context: Context) = synchronized(runtimeLock) {
            val runtime = currentRuntime(context)
            val roots = listOf(runtime.parentFile!!, File(context.codeCacheDir, "media-engine"))
            roots.filterNot { Files.isSymbolicLink(it.toPath()) }.forEach { root ->
                root.listFiles()?.filter { it != runtime && generatedRuntimeName.matches(it.name) }
                    ?.forEach(::deleteComponentFiles)
            }
        }
    }

    private fun prepareRuntime(): File = synchronized(runtimeLock) {
        val payload = File(nativeDir, "libpython.zip.so")
        val ready = currentRuntime(context)
        val cache = ready.parentFile!!
        if (File(ready, "bridge.py").isFile && File(ready, "xpost.py").isFile && File(ready, "usr/etc/tls/cert.pem").isFile) return@synchronized ready
        if (ready.exists()) check(ready.deleteRecursively())
        val staging = File(cache, "preparing")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Cannot create runtime directory" }
        try {
            unpackPython(payload, staging)
            for (script in listOf("bridge.py", "xpost.py")) {
                context.assets.open("engine/$script").use { input ->
                    File(staging, script).outputStream().use { input.copyTo(it) }
                }
            }
            check(File(staging, "usr/etc/tls/cert.pem").isFile)
            check(staging.renameTo(ready)) { "Cannot publish runtime" }
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
        ready
    }

    private fun configureEnvironment(env: MutableMap<String, String>, home: File, diagnostics: File?) {
        ytDlpCacheDir.mkdirs()
        env["PYTHONHOME"] = home.path
        env["LD_LIBRARY_PATH"] = File(home, "lib").path
        env["SSL_CERT_FILE"] = File(home, "etc/tls/cert.pem").path
        env["TMPDIR"] = context.cacheDir.path
        env["HOME"] = context.noBackupFilesDir.path
        env["YT_DLP_CACHE_DIR"] = ytDlpCacheDir.path
        if (diagnostics != null) env["BILIDOWNLOAD_TEST_DIAGNOSTICS"] = "1"
    }

    private fun killWorker() {
        val process: Process?
        val writer: BufferedWriter?
        val reader: BufferedReader?
        synchronized(workerLock) {
            idleJob?.cancel()
            idleJob = null
            process = workerProcess
            writer = workerWriter
            reader = workerReader
            workerProcess = null
            workerArchive = null
            workerWriter = null
            workerReader = null
        }
        runCatching { writer?.close() }
        runCatching { reader?.close() }
        if (process != null && process.isAlive) {
            process.destroyForcibly()
        }
    }

    private fun scheduleIdleShutdown() {
        synchronized(workerLock) {
            idleJob?.cancel()
            idleJob = workerScope.launch {
                delay(90_000L)
                if (workerMutex.tryLock()) {
                    try {
                        killWorker()
                    } finally {
                        workerMutex.unlock()
                    }
                }
            }
        }
    }

    private fun ensureWorker(archive: File, error: (String) -> BiliException): Pair<BufferedWriter, BufferedReader> {
        synchronized(workerLock) {
            idleJob?.cancel()
            idleJob = null
            val alive = workerProcess?.isAlive == true && workerArchive == archive && workerWriter != null && workerReader != null
            if (alive) return workerWriter!! to workerReader!!
        }
        killWorker()
        val runtime = prepareRuntime()
        val home = File(runtime, "usr")
        val builder = ProcessBuilder(
            File(nativeDir, "libpython.so").path, "-B", "-s", "-X", "utf8",
            File(runtime, "bridge.py").path, archive.absolutePath, File(nativeDir, "libqjs.so").path, "--worker",
        )
            .directory(context.cacheDir)
            .redirectError(ProcessBuilder.Redirect.to(File("/dev/null")))
        configureEnvironment(builder.environment(), home, null)
        val process = builder.start()
        val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
        val reader = process.inputStream.bufferedReader(Charsets.UTF_8)
        try {
            val readyLine = reader.readLine() ?: throw error("Engine")
            if (!JSONObject(readyLine).optBoolean("ready", false)) throw error("Engine")
        } catch (e: BiliException) {
            process.destroyForcibly()
            throw e
        } catch (_: Exception) {
            process.destroyForcibly()
            throw error("Engine")
        }
        synchronized(workerLock) {
            workerProcess = process
            workerArchive = archive
            workerWriter = writer
            workerReader = reader
        }
        return writer to reader
    }

    suspend fun warmUp() = withContext(Dispatchers.IO) {
        runCatching {
            if (!BiliApplication.components.state.value.ready) return@runCatching
            val archive = requireArchive(false)
            workerMutex.withLock {
                currentCoroutineContext().ensureActive()
                ensureWorker(archive) { BiliException("") }
                scheduleIdleShutdown()
            }
        }
        Unit
    }

    private suspend fun runWithWorker(
        request: JSONObject,
        archive: File,
        error: (String) -> BiliException,
    ): JSONObject = workerMutex.withLock {
        currentCoroutineContext().ensureActive()
        val (writer, reader) = ensureWorker(archive, error)
        coroutineScope {
            var completed = false
            val cancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    if (!completed) killWorker()
                }
            }
            try {
                ensureActive()
                writer.write(request.toString())
                writer.newLine()
                writer.flush()
                while (true) {
                    ensureActive()
                    val line = reader.readLine() ?: throw IOException("Worker EOF")
                    val event = JSONObject(line)
                    if (event.has("error")) {
                        completed = true
                        throw error(event.getString("error"))
                    }
                    event.optJSONObject("result")?.let {
                        completed = true
                        return@coroutineScope it
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                throw error("Engine")
            } catch (e: BiliException) {
                throw e
            } catch (e: CancellationException) {
                killWorker()
                throw e
            } catch (_: Exception) {
                killWorker()
                currentCoroutineContext().ensureActive()
                throw error("Engine")
            } finally {
                completed = true
                cancellation.cancel()
                scheduleIdleShutdown()
            }
        }
    }

    suspend fun run(request: JSONObject, directory: File?, progress: (Float) -> Unit,
                    error: (String) -> BiliException): JSONObject = slots.withPermit {
        val x = request.optString("platform") == "x"
        val archive = requireArchive(x)
        if (request.optString("op") != "fetch" && testDiagnostics == null) {
            return@withPermit runWithWorker(request, archive, error)
        }
        val diagnostics = testDiagnostics?.let { File.createTempFile("runtime-diagnostics-", ".log", context.cacheDir) }
        try {
            val runtime = prepareRuntime()
            currentCoroutineContext().ensureActive()
            val home = File(runtime, "usr")
            val builder = ProcessBuilder(File(nativeDir, "libpython.so").path, "-B", "-s", "-X", "utf8",
                File(runtime, "bridge.py").path, archive.absolutePath, File(nativeDir, "libqjs.so").path)
                .directory(directory ?: context.cacheDir)
                .redirectError(ProcessBuilder.Redirect.to(diagnostics ?: File("/dev/null")))
            configureEnvironment(builder.environment(), home, diagnostics)
            val process = builder.start()
            try {
                coroutineScope {
                    // Killing the child releases a pipe blocked in readLine, including on metadata timeout.
                    val cancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally { process.destroyForcibly() }
                    }
                    try {
                        ensureActive()
                        process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(request.toString()); it.newLine() }
                        var result: JSONObject? = null
                        process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                            lines.forEach { line ->
                                ensureActive()
                                val event = JSONObject(line)
                                if (event.has("error")) throw error(event.getString("error"))
                                if (event.has("progress")) progress(event.getDouble("progress").toFloat().coerceIn(0f, 1f))
                                event.optJSONObject("result")?.let { result = it }
                            }
                        }
                        ensureActive()
                        if (process.waitFor() != 0) throw error("Engine")
                        result ?: throw error("Engine")
                    } finally {
                        cancellation.cancel()
                    }
                }
            } finally {
                if (process.isAlive) process.destroyForcibly()
                process.waitFor()
            }
        } catch (e: BiliException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            testDiagnostics?.invoke(e.toString())
            throw error("Engine")
        } finally {
            diagnostics?.let {
                it.reader().use { reader ->
                    val buffer = CharArray(4096)
                    val size = reader.read(buffer)
                    if (size > 0) testDiagnostics?.invoke(String(buffer, 0, size))
                }
                it.delete()
            }
        }
    }
}

// The signed APK supplies a ZIP containing the runtime and same-directory shared library aliases.
// java.util.zip does not expose Unix mode bits. In this pinned runtime every alias is a short file
// of the form usr/lib/lib*.so[.version] whose entire content is another such library's filename.
internal fun unpackPython(payload: File, destination: File) {
    val root = destination.canonicalFile
    val soName = Regex("""lib[A-Za-z0-9_.+-]+\.so(?:\.[A-Za-z0-9_.+-]+)?""")
    val aliases = mutableMapOf<File, String>()
    ZipFile(payload).use { zip ->
        for (entry in zip.entries()) {
            val name = entry.name.removeSuffix("/")
            require((name == "usr" || name.startsWith("usr/")) &&
                name.split('/').none { it.isEmpty() || it == "." || it == ".." } && '\\' !in name)
            val file = File(root, name).canonicalFile
            require(file.toPath().startsWith(root.toPath()))
            if (entry.isDirectory) {
                check(file.mkdirs() || file.isDirectory)
                continue
            }
            check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
            zip.getInputStream(entry).use { input ->
                if (file.parentFile == File(root, "usr/lib") && soName.matches(file.name) && entry.size in 1L..128L) {
                    val target = input.readBytes().toString(Charsets.US_ASCII)
                    require(soName.matches(target) && zip.getEntry("usr/lib/$target") != null)
                    aliases[file] = target
                } else {
                    file.outputStream().use { out -> check(input.copyTo(out) == entry.size) }
                }
            }
        }
    }
    for (alias in aliases.keys) {
        var target = alias
        val seen = mutableSetOf<File>()
        while (target in aliases) {
            require(seen.add(target)) { "Cyclic library alias" }
            target = File(target.parentFile, aliases.getValue(target))
        }
        require(target.isFile) { "Missing library target" }
        // The linker needs the same ELF under each SONAME. Hard links also keep
        // the extracted directory free of symbolic links during cleanup.
        try {
            Files.createLink(alias.toPath(), target.toPath())
        } catch (_: IOException) {
            Files.copy(target.toPath(), alias.toPath())
        }
    }
}
