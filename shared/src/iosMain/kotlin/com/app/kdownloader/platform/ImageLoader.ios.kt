package com.app.kdownloader.platform

// Thumbnail downloading is not implemented for iOS yet; the shared UI shows a placeholder.
actual suspend fun fetchImageBytes(url: String): ByteArray? = null
