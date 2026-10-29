package com.app.kdownloader.platform

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID

actual val supportsYtDlpDownloads: Boolean = true

private const val TAG = "KDownloader"
private const val DOWNLOAD_SUBFOLDER = "KDownloader"
private const val METADATA_TIMEOUT_MS = 30_000L

/** Public location the user sees; scoped storage forbids yt-dlp from writing here directly. */
private val publishedDirectory: String
    get() = "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_SUBFOLDER"

/** Serialises the one-time, slow unpacking of the bundled Python/ffmpeg payload. */
private val toolsMutex = Mutex()

@Volatile
private var ytDlpReady = false

@Volatile
private var ffmpegReady = false

actual fun defaultDownloadDirectory(): String {
    val context = AndroidAppContext.current ?: return ""
    return stagingDirectory(context).absolutePath
}

actual suspend fun probeYtDlp(): YtDlpInfo = withContext(Dispatchers.IO) {
    val context = AndroidAppContext.current
    if (context == null) {
        return@withContext YtDlpInfo(
            platformSupported = true,
            error = "La app todavía no está inicializada. Vuelve a abrirla.",
        )
    }

    val error = ensureTools(context)
    if (error != null) {
        YtDlpInfo(
            platformSupported = true,
            ffmpegAvailable = ffmpegReady,
            error = error,
            fixedDownloadDirectory = publishedDirectory,
        )
    } else {
        YtDlpInfo(
            platformSupported = true,
            executable = "yt-dlp integrado",
            version = readBundledVersion(),
            ffmpegAvailable = ffmpegReady,
            fixedDownloadDirectory = publishedDirectory,
        )
    }
}

actual suspend fun fetchVideoMetadata(url: String): VideoMetadata? = coroutineScope {
    val context = AndroidAppContext.current ?: return@coroutineScope null
    // Reuses the same lazy bootstrap as downloads (and its 24 h update check).
    if (ensureTools(context) != null) return@coroutineScope null

    val processId = "kdownloader-meta-${UUID.randomUUID()}"
    // Cancelling (timeout or an abandoned analysis) must stop the native process, not just the
    // coroutine, exactly like the download flow does.
    val cancellationWatcher = launch(Dispatchers.IO) {
        try {
            awaitCancellation()
        } finally {
            YoutubeDL.getInstance().destroyProcessById(processId)
        }
    }

    try {
        withTimeoutOrNull(METADATA_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                val request = YoutubeDLRequest(url).addCommands(metadataOptions())
                val response = YoutubeDL.getInstance()
                    .execute(request, processId, redirectErrorStream = true)
                response.out.lineSequence().firstNotNullOfOrNull(::parseMetadataLine)
            }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        Log.d(TAG, "No se pudo leer los metadatos del enlace", error)
        null
    } finally {
        cancellationWatcher.cancel()
    }
}

actual fun ytDlpDownload(request: DownloadRequest): Flow<DownloadEvent> = channelFlow {
    val context = AndroidAppContext.current
    if (context == null) {
        send(
            DownloadEvent.Finished(
                success = false,
                message = "La app todavía no está inicializada. Cierra y vuelve a abrir KDownloader.",
            ),
        )
        return@channelFlow
    }

    val initError = ensureTools(context)
    if (initError != null) {
        send(DownloadEvent.Finished(success = false, message = initError))
        return@channelFlow
    }

    val outputDirectory = request.outputDirectory.trim()
        .takeIf { it.isNotEmpty() }
        ?.let(::File)
        ?: stagingDirectory(context)
    if (!outputDirectory.isDirectory && !outputDirectory.mkdirs()) {
        send(
            DownloadEvent.Finished(
                success = false,
                message = "No se pudo crear la carpeta de destino: ${outputDirectory.path}",
            ),
        )
        return@channelFlow
    }

    val workingRequest = request.copy(outputDirectory = outputDirectory.absolutePath)
    val options = buildYtDlpOptions(workingRequest, ffmpegAvailable = ffmpegReady)
    val ytDlpRequest = YoutubeDLRequest(request.url).addCommands(options)
    send(DownloadEvent.Log("$ yt-dlp ${options.joinToString(" ")}"))

    val processId = "kdownloader-${UUID.randomUUID()}"
    // Cancelling the collector must stop the native process, not just the coroutine.
    val cancellationWatcher = launch(Dispatchers.IO) {
        try {
            awaitCancellation()
        } finally {
            YoutubeDL.getInstance().destroyProcessById(processId)
        }
    }

    var downloadedPath: String? = null
    val errors = ArrayDeque<String>()
    var response: YoutubeDLResponse? = null
    var errorMessage: String? = null

    try {
        response = withContext(Dispatchers.IO) {
            YoutubeDL.getInstance().execute(
                ytDlpRequest,
                processId,
                redirectErrorStream = true, // progress and errors arrive through the callback
            ) { _, _, line ->
                if (!line.isNullOrBlank()) {
                    when {
                        line.startsWith(FILE_PREFIX) ->
                            downloadedPath = line.removePrefix(FILE_PREFIX).trim()

                        line.startsWith(PROGRESS_PREFIX) ->
                            parseProgressLine(line)?.let { this@channelFlow.trySend(it) }

                        else -> {
                            if (line.contains("ERROR:", ignoreCase = true)) {
                                errors.addLast(line)
                                if (errors.size > 4) errors.removeFirst()
                            }
                            this@channelFlow.trySend(DownloadEvent.Log(line))
                        }
                    }
                }
            }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (canceled: YoutubeDL.CanceledException) {
        errorMessage = "Descarga cancelada."
    } catch (interrupted: InterruptedException) {
        errorMessage = "Descarga interrumpida."
    } catch (error: YoutubeDLException) {
        errorMessage = describeYtDlpFailure(errors, error.message)
    } finally {
        cancellationWatcher.cancel()
    }

    if (errorMessage == null && response != null && response.exitCode != 0) {
        errorMessage = describeYtDlpFailure(errors, response.err)
    }

    val stagedFile = downloadedPath
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)
        ?.takeIf { it.isFile }

    if (errorMessage == null && stagedFile == null) {
        val raw = describeYtDlpFailure(errors, null)
        errorMessage = if (raw == YT_DLP_UNKNOWN_FAILURE) {
            "yt-dlp terminó sin un archivo de salida."
        } else {
            raw
        }
    }

    if (errorMessage != null) {
        send(DownloadEvent.Finished(success = false, message = errorMessage))
        return@channelFlow
    }

    val file = stagedFile ?: return@channelFlow
    send(DownloadEvent.Log("Guardando en $publishedDirectory…"))

    val publishedPath = withContext(Dispatchers.IO) { publishToDownloads(context, file) }
    val message = if (publishedPath != null) {
        "Descarga completada. Archivo guardado en $publishedDirectory."
    } else {
        "Descarga completada en ${file.parent}."
    }

    send(
        DownloadEvent.Finished(
            success = true,
            outputPath = publishedPath ?: file.absolutePath,
            message = message,
        ),
    )
}

// ---------------------------------------------------------------------------------------------
// youtubedl-android bootstrap
// ---------------------------------------------------------------------------------------------

private suspend fun ensureTools(context: Context): String? = toolsMutex.withLock {
    if (ytDlpReady) {
        return@withLock null
    }

    withContext(Dispatchers.IO) {
        val ytDlpError = runCatching { YoutubeDL.getInstance().init(context) }.exceptionOrNull()
        if (ytDlpError != null) {
            return@withContext "No se pudo preparar yt-dlp: ${ytDlpError.describe()}"
        }
        ytDlpReady = true

        // The bundled binary ages fast; outdated versions fail with "HTTP Error 403" on YouTube.
        refreshYtDlp(context)

        // ffmpeg is optional: without it only combined formats and the original audio container
        // are available, exactly like the desktop implementation.
        ffmpegReady = runCatching { FFmpeg.getInstance().init(context) }.isSuccess
        null
    }
}

private const val UPDATE_PREFS = "kdownloader-ytdlp"
private const val UPDATE_CHECKED_AT = "checkedAt"
private const val UPDATE_INTERVAL_MS = 24L * 60 * 60 * 1000
private const val UPDATE_TIMEOUT_MS = 30_000L

/**
 * Keeps the bundled yt-dlp current.
 *
 * `youtubedl-android` ships whichever yt-dlp existed when the library was released, and sites
 * break older versions regularly (YouTube answers with `HTTP Error 403` once its player changes).
 * The library can swap its binary for the latest GitHub release, so this checks at most once a
 * day. Failures (offline, GitHub unreachable, ...) keep the previous binary and never block the
 * download.
 */
private suspend fun refreshYtDlp(context: Context) {
    val prefs = context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
    val now = System.currentTimeMillis()
    if (now - prefs.getLong(UPDATE_CHECKED_AT, 0L) < UPDATE_INTERVAL_MS) return
    // Record the attempt first so an offline device does not retry on every launch.
    prefs.edit().putLong(UPDATE_CHECKED_AT, now).apply()

    withTimeoutOrNull(UPDATE_TIMEOUT_MS) {
        try {
            val status = YoutubeDL.getInstance().updateYoutubeDL(context)
            if (status == YoutubeDL.UpdateStatus.DONE) {
                Log.i(TAG, "yt-dlp actualizado a la última versión")
            } else {
                Log.d(TAG, "yt-dlp ya está actualizado")
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Log.w(TAG, "No se pudo actualizar yt-dlp", error)
        }
    }
}

private fun readBundledVersion(): String? = runCatching {
    val request = YoutubeDLRequest(emptyList<String>()).addOption("--version")
    YoutubeDL.getInstance()
        .execute(request)
        .out
        .lineSequence()
        .map { it.trim() }
        .lastOrNull { it.isNotEmpty() }
}.getOrNull()

// ---------------------------------------------------------------------------------------------
// Output publishing: scoped storage does not let the Python process write to public Downloads
// directly, so files are downloaded into the app-specific directory and copied into MediaStore.
// ---------------------------------------------------------------------------------------------

private fun stagingDirectory(context: Context): File {
    val root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
    return File(root, DOWNLOAD_SUBFOLDER)
}

private fun publishToDownloads(context: Context, file: File): String? {
    // Pre-Android 10 has no scoped storage: files in the app-specific external dir are already
    // visible to file managers, so there is nothing to copy.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

    val name = file.name
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeFor(file))
        put(MediaStore.MediaColumns.RELATIVE_PATH, publishedDirectory)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }

    val resolver = context.contentResolver
    val uri = runCatching {
        resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    }.getOrNull() ?: return null

    return runCatching {
        resolver.openOutputStream(uri)?.use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
        } ?: error("No se pudo abrir el archivo de destino")

        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null,
        )
        file.delete()
        "$publishedDirectory/$name"
    }.getOrElse {
        runCatching { resolver.delete(uri, null, null) }
        null
    }
}

private fun mimeTypeFor(file: File): String {
    val extension = file.extension.lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        ?: when (extension) {
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "m4a" -> "audio/mp4"
            "opus", "ogg" -> "audio/ogg"
            else -> "application/octet-stream"
        }
}

// ---------------------------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------------------------

private fun Throwable.describe(): String {
    val own = message?.takeIf { it.isNotBlank() } ?: this::class.simpleName ?: "error desconocido"
    val causeMessage = cause?.message?.takeIf { it.isNotBlank() }
    return if (causeMessage != null && !own.contains(causeMessage)) "$own ($causeMessage)" else own
}
