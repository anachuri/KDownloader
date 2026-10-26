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
    )
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
                failureMessage(errors, exitCode)

        else -> failureMessage(errors, exitCode)
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

internal const val PROGRESS_PREFIX = "KDLPROGRESS|"
internal const val FILE_PREFIX = "KDLFILE|"

private const val PROGRESS_TEMPLATE =
    "download:$PROGRESS_PREFIX%(progress.downloaded_bytes)s|%(progress.total_bytes)s|" +
        "%(progress.total_bytes_estimate)s|%(progress._percent_str)s|%(progress._speed_str)s|" +
        "%(progress._eta_str)s"

internal fun buildYtDlpArgs(
    executable: String,
    request: DownloadRequest,
    ffmpegAvailable: Boolean,
): List<String> = buildList {
    add(executable)
    add("--newline") // one line per progress tick instead of carriage-return updates
    add("--no-playlist")
    add("--no-warnings")
    add("--encoding")
    add("utf-8")
    // --print implies --quiet; --no-quiet keeps the progress stream flowing.
    add("--print")
    add("after_move:$FILE_PREFIX%(filepath)s")
    add("--no-quiet")
    add("--no-simulate")
    add("--progress-template")
    add(PROGRESS_TEMPLATE)
    add("--paths")
    add(File(request.outputDirectory).absolutePath)
    add("-o")
    add("%(title).120B [%(id)s].%(ext)s")

    val audioFormat = request.audioFormat
    if (audioFormat != null) {
        if (ffmpegAvailable) {
            add("--extract-audio")
            add("--audio-format")
            add(audioFormat)
            request.audioBitrateKbps?.let { add("--audio-quality"); add("${it}K") }
        } else {
            // Extraction/conversion needs ffmpeg; keep the original container instead of failing.
            add("--format")
            add("bestaudio/best")
        }
    } else {
        val height = request.maxHeight
        val selector = when {
            height == null && ffmpegAvailable -> "bv*+ba/b"
            height == null -> "b"
            ffmpegAvailable -> "bv*[height<=$height]+ba/b[height<=$height]/b"
            else -> "b[height<=$height]/b"
        }
        add("--format")
        add(selector)
    }

    add(request.url)
}

internal fun parseProgressLine(line: String): DownloadEvent.Progress? {
    if (!line.startsWith(PROGRESS_PREFIX)) return null
    val parts = line.removePrefix(PROGRESS_PREFIX).split('|')
    if (parts.size < 6) return null

    val downloaded = parts[0].trim().toLongOrNull()
    val total = parts[1].trim().toLongOrNull() ?: parts[2].trim().toLongOrNull()
    val usableTotal = total?.takeIf { it > 0 }
    val percent = parts[3].trim().removeSuffix("%").trim().toFloatOrNull()
        ?: downloaded?.let { bytes -> usableTotal?.let { bytes * 100f / it } }

    return DownloadEvent.Progress(
        fraction = percent?.let { (it / 100f).coerceIn(0f, 1f) },
        downloadedBytes = downloaded,
        totalBytes = usableTotal,
        speed = parts[4].trim().takeUnless { it.isUnavailable() },
        eta = parts[5].trim().takeUnless { it.isUnavailable() },
    )
}

private fun String.isUnavailable(): Boolean =
    isBlank() || this == "NA" || startsWith("Unknown", ignoreCase = true)

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

private fun failureMessage(errors: List<String>, exitCode: Int): String {
    val raw = errors.lastOrNull() ?: return "yt-dlp terminó con el código $exitCode."
    return when {
        raw.contains("ffmpeg", ignoreCase = true) || raw.contains("ffprobe", ignoreCase = true) ->
            "Falta ffmpeg, necesario para unir imagen y sonido o convertir el audio. " +
                "Instálalo y vuelve a intentarlo.\n$raw"

        raw.contains("Requested format is not available", ignoreCase = true) ->
            "Este video solo ofrece pistas de audio y video separadas. " +
                "Instala ffmpeg para poder unirlas.\n$raw"

        raw.contains("Unsupported URL", ignoreCase = true) ->
            "yt-dlp no reconoce ese enlace como un medio descargable.\n$raw"

        else -> raw
    }
}
