package com.app.kdownloader.platform

// iOS cannot reveal arbitrary paths from the shared framework without a host view controller.
actual suspend fun revealInFolder(path: String): Boolean = false
