package com.app.kdownloader.platform

import android.app.DownloadManager
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android has no "reveal the containing folder" intent. Successful downloads are published to the
 * public Downloads collection (API 29+), so the closest equivalent is opening the system Downloads
 * screen where the file is listed.
 */
actual suspend fun revealInFolder(path: String): Boolean = withContext(Dispatchers.Main) {
    val context = AndroidAppContext.current ?: return@withContext false
    runCatching {
        val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}
