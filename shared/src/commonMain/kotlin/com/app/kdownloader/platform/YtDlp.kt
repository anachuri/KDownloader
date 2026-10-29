package com.app.kdownloader.platform

import kotlinx.coroutines.flow.Flow

/** Message used by platforms that cannot run yt-dlp at all. */
const val YT_DLP_UNSUPPORTED_MESSAGE: String =
    "La descarga no está disponible en esta plataforma."

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

/** Result of looking for the yt-dlp/ffmpeg tooling on the host system. */
data class YtDlpInfo(
    val platformSupported: Boolean,
    val executable: String? = null,
    val version: String? = null,
    val ffmpegAvailable: Boolean = false,
    /** Human-readable reason when the tooling could not be prepared. */
    val error: String? = null,
    /**
     * Location the user cannot change, shown instead of a folder picker. Android publishes
     * finished downloads to the public `Downloads` collection, so a custom path is meaningless.
     */
    val fixedDownloadDirectory: String? = null,
) {
    val available: Boolean get() = executable != null && error == null
}

/**
 * A media stream reported by yt-dlp for the analyzed link.
 *
 * Video streams carry [height] and [videoCodec]; audio-only streams carry [audioCodec] and leave
 * [height] `null`. Values are only used to describe the options, never to build the yt-dlp
 * download command (the app still downloads by [height] cap).
 */
data class VideoFormatInfo(
    val height: Int? = null,
    val fps: Int? = null,
    val extension: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val totalBitrateKbps: Int? = null,
    val fileSizeBytes: Long? = null,
) {
    val isVideo: Boolean get() = height != null && videoCodec != null
    val isAudioOnly: Boolean get() = height == null && audioCodec != null
}

/**
 * Real metadata resolved by yt-dlp without downloading the media.
 *
 * Every field is optional: extractors differ in what they expose, and a missing field must keep
 * the fallback value shown by the demo analysis.
 */
data class VideoMetadata(
    val title: String? = null,
    val durationSeconds: Long? = null,
    val thumbnailUrl: String? = null,
    val formats: List<VideoFormatInfo> = emptyList(),
)

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

/** True when the current platform can run yt-dlp (desktop JVM and Android). */
expect val supportsYtDlpDownloads: Boolean

/** Detects yt-dlp and ffmpeg without blocking the UI thread. */
expect suspend fun probeYtDlp(): YtDlpInfo

/** Folder suggested by the host OS (created on demand when downloading). */
expect fun defaultDownloadDirectory(): String

/**
 * Resolves the title, duration, thumbnail and available streams without downloading anything.
 *
 * Returns `null` when the platform cannot run yt-dlp or the extraction fails; callers keep the
 * demo values in that case. Desktop and Android always return `null` on failure instead of
 * throwing, so the UI can resolve metadata optimistically.
 */
expect suspend fun fetchVideoMetadata(url: String): VideoMetadata?

/**
 * Runs yt-dlp and streams its progress.
 *
 * Cancelling the collector must terminate the child process; implementations are responsible for
 * that so the UI can offer a working "Cancelar" button.
 */
expect fun ytDlpDownload(request: DownloadRequest): Flow<DownloadEvent>
