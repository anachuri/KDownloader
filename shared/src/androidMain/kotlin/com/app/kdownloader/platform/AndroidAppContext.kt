package com.app.kdownloader.platform

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri

/**
 * Process-wide application context captured before any Activity exists.
 *
 * Shared platform code (yt-dlp, file reveal) needs a Context, but the shared module has no
 * Activity of its own. The provider below installs it during process startup.
 */
internal object AndroidAppContext {
    @Volatile
    private var value: Context? = null

    val current: Context?
        get() = value

    fun install(context: Context) {
        value = context.applicationContext
    }
}

/**
 * Content providers are instantiated by the framework before the first Activity, so this gives
 * the shared module an application context without glue code in `:androidApp`.
 *
 * Initialising the bundled Python/ffmpeg payload is deliberately *not* done here: it is slow and
 * would block app startup. [probeYtDlp] and [ytDlpDownload] run it on the IO dispatcher instead.
 */
class YtDlpInitializer : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.applicationContext?.let(AndroidAppContext::install)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
