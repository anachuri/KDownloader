package com.app.kdownloader.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

actual suspend fun revealInFolder(path: String): Boolean = withContext(Dispatchers.IO) {
    val requested = File(path)
    val target = when {
        requested.exists() -> requested
        requested.parentFile?.exists() == true -> requested.parentFile
        else -> return@withContext false
    }
    val osName = System.getProperty("os.name").orEmpty()
    revealAttempts(osName, target).any(::launchCommand)
}

/**
 * Builds the candidate commands used to reveal [target] in the system file manager.
 *
 * Split out from [revealInFolder] so the OS-specific argument handling can be unit tested. Every
 * list is tried in order until one of them starts successfully.
 */
internal fun revealAttempts(
    osName: String,
    target: File,
    isDirectory: Boolean = target.isDirectory,
): List<List<String>> {
    val path = target.absolutePath
    val parent = target.parentFile?.absolutePath
    val normalized = osName.lowercase()

    return when {
        normalized.startsWith("windows") -> buildList {
            if (isDirectory) {
                add(listOf("explorer.exe", path))
            } else {
                add(listOf("explorer.exe", "/select,$path"))
                parent?.let { add(listOf("explorer.exe", it)) }
            }
        }

        normalized.startsWith("mac") || normalized.startsWith("darwin") -> buildList {
            if (isDirectory) {
                add(listOf("open", path))
            } else {
                add(listOf("open", "-R", path))
                parent?.let { add(listOf("open", it)) }
            }
        }

        else -> buildList {
            if (isDirectory) {
                add(listOf("xdg-open", path))
            } else {
                // FileManager1 is the freedesktop standard and selects the file itself.
                // gdbus is used instead of dbus-send because dbus-send splits array items on
                // commas, which breaks filenames such as "Artist, Song (Video).webm".
                add(
                    listOf(
                        "gdbus",
                        "call",
                        "--session",
                        "--dest", "org.freedesktop.FileManager1",
                        "--object-path", "/org/freedesktop/FileManager1",
                        "--method", "org.freedesktop.FileManager1.ShowItems",
                        "[${gvariantString(fileManagerUri(target))}]",
                        "''",
                    ),
                )
                // Portable fallback: just open the containing folder.
                parent?.let { add(listOf("xdg-open", it)) }
            }
        }
    }
}

/**
 * Builds a `file:///…` URI for [target]. `File.toURI()` emits the single-slash form
 * (`file:/home/…`), which some file managers do not normalize.
 */
internal fun fileManagerUri(target: File): String {
    val uri = target.toURI().toASCIIString()
    return when {
        uri.startsWith("file:///") -> uri
        uri.startsWith("file:/") -> "file:///" + uri.removePrefix("file:/")
        else -> uri
    }
}

/** Quotes [value] as a GVariant text-format string literal. */
internal fun gvariantString(value: String): String =
    "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"

private fun launchCommand(command: List<String>): Boolean = runCatching {
    val process = ProcessBuilder(command)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()

    if (!process.waitFor(3, TimeUnit.SECONDS)) {
        // Some file managers keep the helper process alive while the window is open.
        true
    } else {
        // explorer.exe is known to exit with 1 even when it opened the folder.
        process.exitValue() == 0 || File(command.first()).name.equals("explorer.exe", ignoreCase = true)
    }
}.getOrDefault(false)
