package com.app.kdownloader.platform

import kotlinx.coroutines.flow.Flow

/** Message used by platforms that cannot spawn external processes. */
const val YT_DLP_UNSUPPORTED_MESSAGE: String =
    "La descarga con yt-dlp solo está disponible en la app de escritorio."

/**
 * Everything needed to build a single yt-dlp invocation.
 *
 * When [audioFormat] is set the download is audio-only; otherwise [maxHeight] selects the best
 * video format up to that height (`null` means "best available").
 */
data class DownloadRequest(
    val url: String,
    val outputDirectory: String,
    val maxHeight: Int? = null,
    val audioFormat: String? = null,
    val audioBitrateKbps: Int? = null,
) {
    val isAudioOnly: Boolean get() = audioFormat != null
}

/** Result of looking for the yt-dlp/ffmpeg binaries on the host system. */
data class YtDlpInfo(
    val platformSupported: Boolean,
    val executable: String? = null,
    val version: String? = null,
    val ffmpegAvailable: Boolean = false,
) {
    val available: Boolean get() = executable != null
}

/** Events streamed while a yt-dlp process runs. */
sealed interface DownloadEvent {
    /** A progress tick. [fraction] is `0f..1f`, or `null` when the total size is unknown. */
    data class Progress(
        val fraction: Float?,
        val downloadedBytes: Long? = null,
        val totalBytes: Long? = null,
        val speed: String? = null,
        val eta: String? = null,
    ) : DownloadEvent

    /** A raw line printed by yt-dlp (useful as a live console). */
    data class Log(val line: String) : DownloadEvent

    /** Terminal event; exactly one is emitted per successful subscription. */
    data class Finished(
        val success: Boolean,
        val outputPath: String? = null,
        val message: String,
    ) : DownloadEvent
}

/** True when the current platform can run yt-dlp as a child process (desktop JVM only). */
expect val supportsYtDlpDownloads: Boolean

/** Detects yt-dlp and ffmpeg without blocking the UI thread. */
expect suspend fun probeYtDlp(): YtDlpInfo

/** Folder suggested by the host OS (created on demand when downloading). */
expect fun defaultDownloadDirectory(): String

/**
 * Runs yt-dlp and streams its progress.
 *
 * Cancelling the collector must terminate the child process; implementations are responsible for
 * that so the UI can offer a working "Cancelar" button.
 */
expect fun ytDlpDownload(request: DownloadRequest): Flow<DownloadEvent>
