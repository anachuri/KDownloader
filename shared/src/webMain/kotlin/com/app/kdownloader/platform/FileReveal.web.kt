package com.app.kdownloader.platform

// Browsers have no access to the local file system, so there is nothing to reveal.
actual suspend fun revealInFolder(path: String): Boolean = false
