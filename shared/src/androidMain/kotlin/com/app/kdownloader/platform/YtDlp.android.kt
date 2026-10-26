package com.app.kdownloader.platform

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

// Android apps cannot run a locally installed yt-dlp binary, so the shared UI disables downloads.
actual val supportsYtDlpDownloads: Boolean = false

actual suspend fun probeYtDlp(): YtDlpInfo = YtDlpInfo(platformSupported = false)

actual fun defaultDownloadDirectory(): String = ""

actual fun ytDlpDownload(request: DownloadRequest): Flow<DownloadEvent> =
    flowOf(DownloadEvent.Finished(success = false, message = YT_DLP_UNSUPPORTED_MESSAGE))
