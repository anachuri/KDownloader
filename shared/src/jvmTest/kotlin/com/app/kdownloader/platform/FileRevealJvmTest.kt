package com.app.kdownloader.platform

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileRevealJvmTest {

    private val video = File("/home/user/Downloads/KDownloader/Video 1080p.mp4")

    @Test
    fun missingPathIsRejected() = runBlocking {
        assertFalse(revealInFolder("/definitely/not/here/kdownloader-demo.mp4"))
    }

    @Test
    fun windowsSelectsTheFileInExplorer() {
        val attempts = revealAttempts("Windows 11", video, isDirectory = false)

        val first = attempts.first()
        assertEquals("explorer.exe", first.first())
        assertEquals("/select,${video.absolutePath}", first[1])
        assertEquals(listOf("explorer.exe", video.parent), attempts[1])
    }

    @Test
    fun windowsOpensDirectoryDirectly() {
        val folder = File("/home/user/Downloads/KDownloader")

        val attempts = revealAttempts("Windows 10", folder, isDirectory = true)

        assertEquals(listOf(listOf("explorer.exe", folder.absolutePath)), attempts)
    }

    @Test
    fun macOsRevealsWithOpenR() {
        val attempts = revealAttempts("Mac OS X", video, isDirectory = false)

        assertEquals(listOf("open", "-R", video.absolutePath), attempts.first())
        assertEquals(listOf("open", video.parent), attempts[1])
    }

    @Test
    fun linuxUsesFileManager1ThenXdgOpen() {
        val attempts = revealAttempts("Linux", video, isDirectory = false)

        val gdbus = attempts.first()
        assertEquals("gdbus", gdbus.first())
        assertEquals("org.freedesktop.FileManager1.ShowItems", gdbus.valueAfter("--method"))
        assertEquals("[${gvariantString(fileManagerUri(video))}]", gdbus[gdbus.lastIndex - 1])
        assertEquals(listOf("xdg-open", video.parent), attempts.last())
    }

    @Test
    fun linuxKeepsCommasInsideTheUri() {
        val withComma = File("/home/user/Downloads/KDownloader/Playa Del Inglés - Quevedo, Myke Towers.webm")

        val uriArgument = revealAttempts("Linux", withComma, isDirectory = false)
            .first()
            .let { it[it.lastIndex - 1] }
        val expected = "[${gvariantString(fileManagerUri(withComma))}]"

        assertEquals(expected, uriArgument)
        // A single GVariant string literal: dbus-send would split this into two array items.
        assertTrue(uriArgument.contains("Quevedo,%20Myke%20Towers"))
        assertEquals(1, Regex("""'file:///""").findAll(uriArgument).count())
    }

    @Test
    fun unknownOsFallsBackToXdgOpen() {
        val attempts = revealAttempts("FreeBSD", video, isDirectory = false)

        assertTrue(attempts.any { it.first() == "xdg-open" })
    }

    @Test
    fun fileUriAlwaysUsesTripleSlash() {
        val uri = fileManagerUri(video)

        assertTrue(uri.startsWith("file:///"))
        assertTrue(uri.endsWith("Video%201080p.mp4"))
    }

    private fun List<String>.valueAfter(flag: String): String {
        val index = indexOf(flag)
        assertTrue(index >= 0 && index < lastIndex, "missing $flag in $this")
        return get(index + 1)
    }
}
