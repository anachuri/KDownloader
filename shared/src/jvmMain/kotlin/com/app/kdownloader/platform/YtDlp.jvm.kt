package com.app.kdownloader.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

actual val supportsYtDlpDownloads: Boolean = true

actual fun defaultDownloadDirectory(): String {
    val home = System.getProperty("user.home").orEmpty().ifBlank { "." }
    val downloads = File(home, "Downloads")
    return File(if (downloads.isDirectory) downloads else File(home), "KDownloader").absolutePath
}

actual suspend fun probeYtDlp(): YtDlpInfo = withContext(Dispatchers.IO) {
    val executable = resolveYtDlp()
    YtDlpInfo(
        platformSupported = true,
        executable = executable,
        version = executable?.let(::readVersion),
        ffmpegAvailable = hasFfmpeg(),
        error = if (executable == null) {
            "No se encontró yt-dlp. Instálalo o define la variable YT_DLP_PATH " +
                "con la ruta al ejecutable."
        } else {
            null
        },
    )
}

private const val METADATA_TIMEOUT_SECONDS = 20L

actual suspend fun fetchVideoMetadata(url: String): VideoMetadata? = withContext(Dispatchers.IO) {
    val executable = resolveYtDlp() ?: return@withContext null
    val command = listOf(executable) + metadataOptions() + url
    val process = runCatching {
        ProcessBuilder(command).redirectErrorStream(true).start()
    }.getOrNull() ?: return@withContext null

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
            return@withContext null
        }
        reader.join(2_000)
        parseMetadataOutput(output.toString())
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
                message = "No se encontró yt-dlp. Instálalo y asegúrate de que esté en el PATH, " +
                    "o define la variable de entorno YT_DLP_PATH con la ruta al ejecutable.",
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

    val ffmpeg = hasFfmpeg()
    val normalized = request.copy(outputDirectory = directory)
    val command = buildYtDlpArgs(executable, normalized, ffmpeg)
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
        success && request.isAudioOnly && !ffmpeg ->
            "Audio guardado sin convertir (${File(finishedPath.orEmpty()).extension.ifBlank { "original" }}). " +
                "Instala ffmpeg para obtener el formato elegido."

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
): List<String> = buildList {
    add(executable)
    // Desktop keeps resolving relative folders against its own working directory.
    val absoluteOutput = request.copy(
        outputDirectory = File(request.outputDirectory).absolutePath,
    )
    addAll(buildYtDlpOptions(absoluteOutput, ffmpegAvailable))
    add(request.url)
}

// ---------------------------------------------------------------------------------------------
// Host tooling
// ---------------------------------------------------------------------------------------------

private val ytDlpExecutable: String? by lazy { detectYtDlp() }
private val ffmpegDetected: Boolean by lazy { executableRuns("ffmpeg", "-version") }

private fun resolveYtDlp(): String? = ytDlpExecutable

private fun hasFfmpeg(): Boolean = ffmpegDetected

private fun detectYtDlp(): String? {
    System.getenv("YT_DLP_PATH")?.trim()?.takeIf { it.isNotEmpty() }?.let { configured ->
        val file = File(configured)
        if (file.isFile && file.canExecute()) return file.absolutePath
        if (executableRuns(configured, "--version")) return configured
    }

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
    return candidates.firstOrNull { it.isFile && it.canExecute() }?.absolutePath
}

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
