package com.app.kdownloader.platform

/**
 * Downloads raw image bytes for the video preview.
 *
 * Implementations must swallow network errors and return `null` so the UI can fall back to a
 * placeholder thumbnail.
 */
expect suspend fun fetchImageBytes(url: String): ByteArray?
