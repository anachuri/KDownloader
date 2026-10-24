package com.app.kdownloader

import com.app.kdownloader.model.analyzeLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoAnalysisTest {

    @Test
    fun parsesYoutubeLink() {
        val analysis = assertNotNull(analyzeLink("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))

        assertEquals("YouTube", analysis.platform)
        assertTrue(analysis.thumbnailUrl?.contains("dQw4w9WgXcQ") == true)
        assertTrue(analysis.videoOptions.any { it.label == "1080p" && it.recommended })
        assertEquals(4, analysis.audioOptions.size)
    }

    @Test
    fun parsesShortYoutubeLink() {
        val analysis = assertNotNull(analyzeLink("https://youtu.be/dQw4w9WgXcQ"))

        assertEquals("YouTube", analysis.platform)
        assertTrue(analysis.thumbnailUrl?.contains("dQw4w9WgXcQ") == true)
    }

    @Test
    fun rejectsInvalidLinks() {
        assertNull(analyzeLink(""))
        assertNull(analyzeLink("no es un enlace"))
        assertNull(analyzeLink("ftp://example.com/video.mp4"))
    }
}
