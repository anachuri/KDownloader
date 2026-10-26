package com.app.kdownloader.platform

/**
 * Asks the operating system's file manager to reveal [path] (usually a finished download).
 *
 * Returns `true` when a file manager was launched, `false` when the path does not exist or no
 * handler is available. Implementations must never throw.
 */
expect suspend fun revealInFolder(path: String): Boolean
