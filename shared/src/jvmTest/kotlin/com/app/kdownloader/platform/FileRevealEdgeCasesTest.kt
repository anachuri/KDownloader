package com.app.kdownloader.platform

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** OS-specific argument details that [FileRevealJvmTest] does not cover. */
class FileRevealEdgeCasesTest {

    @Test
    fun escapesGvariantBackslashesAndQuotes() {
        assertEquals("'a\\'b\\\\c\"d'", gvariantString("a'b\\c\"d"))
        assertEquals("'plain name.webm'", gvariantString("plain name.webm"))
    }

    @Test
    fun encodesSpecialCharactersInTheFileUri() {
        val uri = fileManagerUri(File("/tmp/a b/café #1.webm"))

        assertTrue(uri.startsWith("file:///"), uri)
        assertTrue(uri.contains("a%20b"), uri)
        assertTrue(uri.contains("caf%C3%A9"), uri)
        assertTrue(uri.contains("%231.webm"), uri)
    }

    @Test
    fun directoriesAreOpenedInsteadOfSelected() {
        val folder = File("/home/user/Downloads/KDownloader")

        assertEquals(
            listOf(listOf("open", folder.absolutePath)),
            revealAttempts("Mac OS X", folder, isDirectory = true),
        )
        assertEquals(
            listOf(listOf("xdg-open", folder.absolutePath)),
            revealAttempts("Linux", folder, isDirectory = true),
        )
    }

    @Test
    fun aFileAtTheFilesystemRootStillHasAParentToOpen() {
        val attempts = revealAttempts("Windows 11", File("/video.mp4"), isDirectory = false)

        assertEquals("explorer.exe", attempts[0].first())
        assertEquals("/select,/video.mp4", attempts[0][1])
        assertEquals(listOf("explorer.exe", "/"), attempts[1])
    }

    @Test
    fun macOsAndLinuxKeepTheirFallbackOrder() {
        val video = File("/home/user/Downloads/KDownloader/video.mp4")

        assertEquals(
            listOf("open", video.parent),
            revealAttempts("Darwin", video, isDirectory = false)[1],
        )
        assertEquals(
            listOf("xdg-open", video.parent),
            revealAttempts("Linux", video, isDirectory = false).last(),
        )
    }

    @Test
    fun unknownOperatingSystemsUseXdgOpenForFilesAndFolders() {
        val folder = File("/home/user/Downloads")

        assertEquals(listOf("xdg-open", folder.absolutePath), revealAttempts("FreeBSD", folder, isDirectory = true).single())
    }
}
