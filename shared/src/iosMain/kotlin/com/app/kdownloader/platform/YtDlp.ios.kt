package com.app.kdownloader.platform

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

// iOS does not allow spawning yt-dlp, so the shared UI disables downloads.
actual val supportsYtDlpDownloads: Boolean = false

actual suspend fun probeYtDlp(): YtDlpInfo = YtDlpInfo(platformSupported = false)

actual fun defaultDownloadDirectory(): String = ""

actual suspend fun fetchVideoMetadata(url: String): VideoMetadata? = null

actual fun ytDlpDownload(request: DownloadRequest): Flow<DownloadEvent> =
    flowOf(DownloadEvent.Finished(success = false, message = YT_DLP_UNSUPPORTED_MESSAGE))
