package com.app.kdownloader.platform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

actual val supportsYtDlpDownloads: Boolean = true

actual fun defaultDownloadDirectory(): String {
    val home = System.getProperty("user.home").orEmpty().ifBlank { "." }
    val downloads = File(home, "Downloads")
    return File(if (downloads.isDirectory) downloads else File(home), "KDownloader").absolutePath
}

actual suspend fun probeYtDlp(): YtDlpInfo = withContext(Dispatchers.IO) {
    // Desktop installers ship a verified yt-dlp; keep it current without requiring the user to
    // install anything. The check is throttled and every failure falls back to the bundled copy.
    refreshManagedYtDlp()

    val executable = resolveYtDlp()
    YtDlpInfo(
        platformSupported = true,
        executable = executable,
        version = executable?.let(::readVersion),
        ffmpegAvailable = hasFfmpeg(),
        error = if (executable == null) {
            "No se encontró yt-dlp. Reinstala KDownloader o define la variable YT_DLP_PATH " +
                "con la ruta al ejecutable."
        } else {
            null
        },
    )
}

private const val METADATA_TIMEOUT_SECONDS = 20L

actual suspend fun fetchVideoMetadata(url: String): VideoMetadata? = withContext(Dispatchers.IO) {
    val executable = resolveYtDlp() ?: return@withContext null

    fetchMetadataWithFallback { args ->
        runMetadataProcess(executable, args + url)
    }
}

/** Runs one metadata extraction and returns its stdout, or `null` on timeout/launch failure. */
private fun runMetadataProcess(executable: String, command: List<String>): String? {
    val process = runCatching {
        ProcessBuilder(listOf(executable) + command).redirectErrorStream(true).start()
    }.getOrNull() ?: return null

    // The formats JSON can exceed the OS pipe buffer (~64 KiB on Linux/macOS), so stdout must be
    // drained while the process runs; waiting first would deadlock until the timeout kills it.
    val output = StringBuilder()
    val reader = Thread {
        runCatching {
            process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                output.append(reader.readText())
            }
        }
    }.apply {
        isDaemon = true
        name = "kdownloader-ytdlp-metadata"
        start()
    }

    try {
        process.outputStream.close() // yt-dlp never reads stdin
        if (!process.waitFor(METADATA_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            stopProcess(process)
            return null
        }
        reader.join(2_000)
        return output.toString()
    } finally {
        if (process.isAlive) stopProcess(process)
    }
}

actual fun ytDlpDownload(request: DownloadRequest): Flow<DownloadEvent> = channelFlow {
    val executable = resolveYtDlp()
    if (executable == null) {
        send(
            DownloadEvent.Finished(
                success = false,
                message = "No se encontró yt-dlp. Reinstala KDownloader o define la variable de " +
                    "entorno YT_DLP_PATH con la ruta al ejecutable.",
            ),
        )
        return@channelFlow
    }

    val directory = File(request.outputDirectory.ifBlank { defaultDownloadDirectory() }).absolutePath
    val directoryFile = File(directory)
    if (!directoryFile.isDirectory && !directoryFile.mkdirs()) {
        send(
            DownloadEvent.Finished(
                success = false,
                message = "No se pudo crear la carpeta de destino: $directory",
            ),
        )
        return@channelFlow
    }

    val ffmpeg = resolveFfmpeg()
    val normalized = request.copy(outputDirectory = directory)
    val command = buildYtDlpArgs(executable, normalized, ffmpeg != null, ffmpeg)
    send(DownloadEvent.Log("$ ${command.joinToString(" ")}"))

    val process = try {
        ProcessBuilder(command)
            .directory(directoryFile)
            .redirectErrorStream(true)
            .start()
    } catch (error: Exception) {
        send(
            DownloadEvent.Finished(
                success = false,
                message = "No se pudo iniciar yt-dlp: ${error.message ?: error::class.simpleName}",
            ),
        )
        return@channelFlow
    }

    // Destroy the child process if the collector cancels (Cancel button, navigation, app exit).
    val cancellationWatcher = launch(Dispatchers.IO) {
        try {
            awaitCancellation()
        } finally {
            stopProcess(process)
        }
    }

    var outputPath: String? = null
    var exitCode = -1
    val errors = ArrayDeque<String>()

    try {
        withContext(Dispatchers.IO) {
            process.outputStream.close() // yt-dlp never reads stdin
            BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    when {
                        line.startsWith(FILE_PREFIX) ->
                            outputPath = line.removePrefix(FILE_PREFIX).trim()

                        line.startsWith(PROGRESS_PREFIX) ->
                            parseProgressLine(line)?.let { send(it) }

                        line.isNotBlank() -> {
                            if (line.contains("ERROR:", ignoreCase = true)) {
                                errors.addLast(line)
                                if (errors.size > 4) errors.removeFirst()
                            }
                            send(DownloadEvent.Log(line))
                        }
                    }
                }
            }
            exitCode = process.waitFor()
        }
    } finally {
        cancellationWatcher.cancel()
        if (process.isAlive) stopProcess(process)
    }

    val finishedPath = outputPath?.takeIf { it.isNotBlank() }
    val fileExists = finishedPath?.let { File(it).isFile } == true
    val success = exitCode == 0 && (finishedPath == null || fileExists)

    val message = when {
        success && request.isAudioOnly && ffmpeg == null ->
            "Audio guardado sin convertir (${File(finishedPath.orEmpty()).extension.ifBlank { "original" }}). " +
                "No se encontró ffmpeg para obtener el formato elegido."

        success -> "Descarga completada."

        finishedPath != null && !fileExists ->
            "yt-dlp terminó con código $exitCode pero no se encontró el archivo final.\n" +
                describeYtDlpFailure(errors, null)

        else -> describeYtDlpFailure(errors, exitCodeFallback(exitCode))
    }

    send(
        DownloadEvent.Finished(
            success = success,
            outputPath = finishedPath.takeIf { fileExists },
            message = message,
        ),
    )
}

// ---------------------------------------------------------------------------------------------
// Command building (internal so the JVM test source set can cover it without touching the system)
// ---------------------------------------------------------------------------------------------

internal fun buildYtDlpArgs(
    executable: String,
    request: DownloadRequest,
    ffmpegAvailable: Boolean,
    ffmpegLocation: String? = null,
): List<String> = buildList {
    add(executable)
    // Desktop keeps resolving relative folders against its own working directory.
    val absoluteOutput = request.copy(
        outputDirectory = File(request.outputDirectory).absolutePath,
    )
    addAll(buildYtDlpOptions(absoluteOutput, ffmpegAvailable, ffmpegLocation))
    add(request.url)
}

// ---------------------------------------------------------------------------------------------
// Bundled / auto-updated yt-dlp
// ---------------------------------------------------------------------------------------------

private const val RESOURCES_DIR_PROPERTY = "compose.application.resources.dir"
private const val TOOLS_SUBFOLDER = "KDownloader/tools"
private const val UPDATE_STAMP_FILE = ".yt-dlp-checked-at"
private const val UPDATE_INTERVAL_MS = 24L * 60 * 60 * 1000
private const val UPDATE_TIMEOUT_MS = 30_000L
private const val YTDLP_LATEST_URL = "https://github.com/yt-dlp/yt-dlp/releases/latest"
private const val YTDLP_DOWNLOAD_BASE = "https://github.com/yt-dlp/yt-dlp/releases/download"
private const val USER_AGENT = "KDownloader"

/** Serialises the once-per-day update check so two probes cannot download at the same time. */
private val updateMutex = Mutex()

private fun isWindows(): Boolean =
    System.getProperty("os.name").orEmpty().lowercase().contains("win")

private fun ytDlpExecutableName(): String = if (isWindows()) "yt-dlp.exe" else "yt-dlp"

private fun ffmpegExecutableName(): String = if (isWindows()) "ffmpeg.exe" else "ffmpeg"

/** Writable per-user folder that receives newer yt-dlp releases. */
private fun toolsDataDirectory(): File {
    val home = System.getProperty("user.home").orEmpty()
    val base = when {
        isWindows() -> System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(home, "AppData/Local")

        System.getProperty("os.name").orEmpty().lowercase().contains("mac") ->
            File(home, "Library/Application Support")

        else -> System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(home, ".local/share")
    }
    return File(base, TOOLS_SUBFOLDER)
}

private fun bundledResourcesDirectory(): File? =
    System.getProperty(RESOURCES_DIR_PROPERTY)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let(::File)
        ?.takeIf { it.isDirectory }

/**
 * Looks for a tool shipped with the installer. Compose merges the contents of
 * `appResourcesRootDir/common` at the root of the resources folder, but the root and the
 * `<os>-<arch>` variant are checked too so a differently packaged build still works.
 */
private fun bundledTool(name: String): File? {
    val root = bundledResourcesDirectory() ?: return null
    val candidates = listOf(
        root.resolve(name),
        root.resolve("common").resolve(name),
        root.resolve(platformResourceDirectory()).resolve(name),
    )
    return candidates.firstOrNull { it.isFile }
}

private fun platformResourceDirectory(): String {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val arch = System.getProperty("os.arch").orEmpty().lowercase()
    val osName = when {
        os.contains("win") -> "windows"
        os.contains("mac") || os.contains("darwin") -> "macos"
        else -> "linux"
    }
    val archName = if (arch.contains("aarch64") || arch.contains("arm64")) "arm64" else "x64"
    return "$osName-$archName"
}

private fun managedTool(name: String): File? =
    File(toolsDataDirectory(), name).takeIf { it.isFile }

private fun runnable(file: File): Boolean = file.isFile && (isWindows() || file.canExecute())

/**
 * Returns an executable copy of a bundled tool.
 *
 * `jpackage`/Compose copy `appResourcesRootDir` without the Unix executable bit, and installed
 * resources live in a read-only location anyway. The first run copies the binary into the
 * per-user tools directory and marks it executable there; later runs reuse it (and the updater
 * can replace it).
 */
private fun ensureExecutableCopy(name: String): File? {
    managedTool(name)?.takeIf(::runnable)?.let { return it }

    val bundled = bundledTool(name) ?: return null
    if (runnable(bundled)) return bundled

    val managed = File(toolsDataDirectory(), name)
    return runCatching {
        managed.parentFile?.mkdirs()
        bundled.copyTo(managed, overwrite = true)
        managed.setExecutable(true, false)
        managed.takeIf(::runnable)
    }.getOrNull()
}

private fun resolveYtDlp(): String? {
    // An explicit override always wins, so users can keep managing their own yt-dlp.
    configuredYtDlp()?.let { return it }
    ensureExecutableCopy(ytDlpExecutableName())?.let { return it.absolutePath }

    // Works when the app inherits a PATH that already contains the binary.
    if (executableRuns("yt-dlp", "--version")) return "yt-dlp"

    // Common install locations, for desktop launchers that do not load the shell PATH.
    val candidates = buildList {
        add(File(System.getProperty("user.home"), ".local/bin/yt-dlp"))
        add(File("/usr/local/bin/yt-dlp"))
        add(File("/usr/bin/yt-dlp"))
        add(File("/opt/homebrew/bin/yt-dlp"))
        System.getenv("LOCALAPPDATA")?.let { add(File(it, "Microsoft/WinGet/Links/yt-dlp.exe")) }
        System.getenv("APPDATA")?.let { add(File(it, "Python/Scripts/yt-dlp.exe")) }
        System.getenv("ProgramFiles")?.let { add(File(it, "yt-dlp/yt-dlp.exe")) }
    }
    return candidates.firstOrNull(::runnable)?.absolutePath
}

private fun resolveFfmpeg(): String? {
    ensureExecutableCopy(ffmpegExecutableName())?.let { return it.absolutePath }
    return if (executableRuns("ffmpeg", "-version")) "ffmpeg" else null
}

private fun configuredYtDlp(): String? =
    System.getenv("YT_DLP_PATH")?.trim()?.takeIf { it.isNotEmpty() }?.let { configured ->
        val file = File(configured)
        when {
            runnable(file) -> file.absolutePath
            executableRuns(configured, "--version") -> configured
            else -> null
        }
    }

private fun hasConfiguredYtDlp(): Boolean =
    !System.getenv("YT_DLP_PATH").isNullOrBlank()

private fun hasFfmpeg(): Boolean = resolveFfmpeg() != null

private fun hasManagedOrBundledYtDlp(): Boolean =
    managedTool(ytDlpExecutableName()) != null || bundledTool(ytDlpExecutableName()) != null

private suspend fun refreshManagedYtDlp() {
    if (hasConfiguredYtDlp() || !hasManagedOrBundledYtDlp()) return
    updateMutex.withLock {
        if (!claimUpdateSlot()) return@withLock
        withTimeoutOrNull(UPDATE_TIMEOUT_MS) {
            try {
                updateManagedYtDlp()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Offline, GitHub unreachable, corrupted download...: keep using the bundled copy.
            }
        }
    }
}

/** Returns `true` at most once per [UPDATE_INTERVAL_MS], recording the attempt on disk. */
private fun claimUpdateSlot(): Boolean {
    val directory = toolsDataDirectory()
    val stamp = File(directory, UPDATE_STAMP_FILE)
    val now = System.currentTimeMillis()
    val lastAttempt = runCatching { stamp.readText().trim().toLong() }.getOrNull() ?: 0L
    if (now - lastAttempt < UPDATE_INTERVAL_MS) return false

    runCatching {
        directory.mkdirs()
        stamp.writeText(now.toString())
    }
    return true
}

private fun updateManagedYtDlp() {
    val installed = managedTool(ytDlpExecutableName()) ?: bundledTool(ytDlpExecutableName())
    val currentVersion = installed?.let { readVersion(it.absolutePath) }
    val latest = latestYtDlpVersion() ?: return
    if (currentVersion != null && !isNewerYtDlpVersion(latest, currentVersion)) return

    val asset = ytDlpAssetName(
        System.getProperty("os.name").orEmpty(),
        System.getProperty("os.arch").orEmpty(),
    ) ?: return
    val expected = fetchExpectedSha256(latest, asset) ?: return

    val directory = toolsDataDirectory().apply { mkdirs() }
    val target = File(directory, ytDlpExecutableName())
    val temp = File(directory, "${target.name}.download")
    try {
        downloadTo("$YTDLP_DOWNLOAD_BASE/$latest/$asset", temp)
        if (!sha256(temp).equals(expected, ignoreCase = true)) return
        if (target.exists() && !target.delete()) return
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        target.setExecutable(true, false)
    } finally {
        temp.delete()
    }
}

/** Resolves the newest release tag from the `/releases/latest` redirect (no API rate limit). */
private fun latestYtDlpVersion(): String? = runCatching {
    val connection = openConnection(YTDLP_LATEST_URL, method = "HEAD", followRedirects = false)
    try {
        connection.getHeaderField("Location")
            .orEmpty()
            .substringAfterLast('/')
            .takeIf { it.isNotBlank() }
    } finally {
        connection.disconnect()
    }
}.getOrNull()

private fun fetchExpectedSha256(version: String, asset: String): String? = runCatching {
    fetchText("$YTDLP_DOWNLOAD_BASE/$version/SHA2-256SUMS")
        .lineSequence()
        .map { it.trim() }
        .firstOrNull { line -> line.substringAfterLast(' ').trimStart('*') == asset }
        ?.substringBefore(' ')
        ?.trim()
        ?.takeIf { it.length == 64 }
}.getOrNull()

private fun fetchText(url: String): String {
    val connection = openConnection(url)
    try {
        val code = connection.responseCode
        check(code in 200..299) { "HTTP $code" }
        return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally {
        connection.disconnect()
    }
}

private fun downloadTo(url: String, target: File) {
    val connection = openConnection(url)
    try {
        val code = connection.responseCode
        check(code in 200..299) { "HTTP $code" }
        connection.inputStream.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    } finally {
        connection.disconnect()
    }
}

private fun openConnection(
    url: String,
    method: String = "GET",
    followRedirects: Boolean = true,
): HttpURLConnection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
    requestMethod = method
    instanceFollowRedirects = followRedirects
    setRequestProperty("User-Agent", USER_AGENT)
    connectTimeout = 10_000
    readTimeout = 20_000
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun ytDlpAssetName(osName: String, arch: String): String? {
    val os = osName.lowercase()
    val cpu = arch.lowercase()
    val arm = cpu.contains("aarch64") || cpu.contains("arm64")
    return when {
        os.contains("win") -> if (arm) "yt-dlp_arm64.exe" else "yt-dlp.exe"
        os.contains("mac") || os.contains("darwin") -> "yt-dlp_macos"
        os.contains("linux") && arm -> "yt-dlp_linux_aarch64"
        os.contains("linux") -> "yt-dlp_linux"
        else -> null
    }
}

/** yt-dlp versions look like `2026.10.26` or `2026.10.26.123456`; compare numerically. */
internal fun isNewerYtDlpVersion(latest: String, current: String): Boolean {
    if (latest == current) return false
    val newParts = latest.split(Regex("[^0-9]+")).mapNotNull { it.toLongOrNull() }
    val oldParts = current.split(Regex("[^0-9]+")).mapNotNull { it.toLongOrNull() }
    for (index in 0 until maxOf(newParts.size, oldParts.size)) {
        val newPart = newParts.getOrElse(index) { 0L }
        val oldPart = oldParts.getOrElse(index) { 0L }
        if (newPart != oldPart) return newPart > oldPart
    }
    return false
}

// ---------------------------------------------------------------------------------------------
// Host tooling
// ---------------------------------------------------------------------------------------------

private fun readVersion(executable: String): String? = runCatching {
    val process = ProcessBuilder(executable, "--version").redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val finished = process.waitFor(10, TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        return@runCatching null
    }
    output.lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() && process.exitValue() == 0 }
}.getOrNull()

private fun executableRuns(vararg command: String): Boolean = runCatching {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    process.outputStream.close()
    process.inputStream.bufferedReader().use { it.readText() }
    val finished = process.waitFor(8, TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        false
    } else {
        process.exitValue() == 0
    }
}.getOrDefault(false)

private fun stopProcess(process: Process) {
    if (!process.isAlive) return
    process.destroy()
    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
}

private fun exitCodeFallback(exitCode: Int): String? =
    if (exitCode != 0) "yt-dlp terminó con el código $exitCode." else null
