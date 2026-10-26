package com.app.kdownloader.platform

// The shared module holds no Activity/Context to launch a file-manager intent, and Android
// downloads are disabled, so there is never a downloaded file to reveal.
actual suspend fun revealInFolder(path: String): Boolean = false
