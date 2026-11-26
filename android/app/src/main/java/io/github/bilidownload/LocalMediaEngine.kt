package io.github.bilidownload

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipFile

internal class LocalMediaEngine(
    private val context: Context,
    private val requireArchive: (Boolean) -> File = { BiliApplication.components.requireArchive(it) }
) {
    private val slots = Semaphore(3)
    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)

    fun taskDirectory(id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(context.cacheDir, "downloads/$id")
    }

    @Synchronized private fun prepareRuntime(): File {
        val payload = File(nativeDir, "libpython.zip.so")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val cache = File(context.codeCacheDir, "media-engine")
        val ready = File(cache, "$version-${payload.length()}")
        if (File(ready, "bridge.py").isFile && File(ready, "xpost.py").isFile && File(ready, "usr/etc/tls/cert.pem").isFile) return ready
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
        cache.listFiles()?.filter { it != ready }?.forEach { it.deleteRecursively() }
        return ready
    }

    suspend fun run(request: JSONObject, directory: File?, progress: (Float) -> Unit,
                    error: (String) -> BiliException): JSONObject = slots.withPermit {
        val x = request.optString("platform") == "x"
        val archive = requireArchive(x)
        try {
            val runtime = prepareRuntime()
            currentCoroutineContext().ensureActive()
            val home = File(runtime, "usr")
            val builder = ProcessBuilder(File(nativeDir, "libpython.so").path, "-B", "-s", "-X", "utf8",
                File(runtime, "bridge.py").path, archive.absolutePath, File(nativeDir, "libqjs.so").path)
                .directory(directory ?: context.cacheDir)
                .redirectError(ProcessBuilder.Redirect.to(File("/dev/null")))
            builder.environment().apply {
                put("PYTHONHOME", home.path)
                put("LD_LIBRARY_PATH", File(home, "lib").path)
                put("SSL_CERT_FILE", File(home, "etc/tls/cert.pem").path)
                put("TMPDIR", context.cacheDir.path)
                put("HOME", context.noBackupFilesDir.path)
            }
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
            throw error("Engine")
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
        Files.createLink(alias.toPath(), target.toPath())
    }
}
