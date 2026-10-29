package com.app.kdownloader

import com.app.kdownloader.model.analyzeLink
import com.app.kdownloader.platform.VideoMetadata
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

    @Test
    fun usesRealMetadataWhenAvailable() {
        val analysis = assertNotNull(
            analyzeLink(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                VideoMetadata(
                    title = "Never Gonna Give You Up",
                    durationSeconds = 213,
                    thumbnailUrl = "https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg",
                ),
            ),
        )

        assertEquals("Never Gonna Give You Up", analysis.title)
        assertEquals("3:33", analysis.duration)
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg", analysis.thumbnailUrl)
    }

    @Test
    fun keepsDemoTitleWhenMetadataIsEmpty() {
        val analysis = assertNotNull(
            analyzeLink("https://www.youtube.com/watch?v=dQw4w9WgXcQ", VideoMetadata()),
        )

        assertEquals("Video de YouTube", analysis.title)
        assertTrue(analysis.thumbnailUrl?.contains("dQw4w9WgXcQ") == true)
    }
}
