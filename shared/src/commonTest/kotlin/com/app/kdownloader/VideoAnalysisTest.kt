package com.app.kdownloader

import com.app.kdownloader.model.analyzeLink
import com.app.kdownloader.platform.VideoFormatInfo
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
    fun keepsPlaceholderTitleWithoutMetadata() {
        val analysis = assertNotNull(
            analyzeLink("https://www.youtube.com/watch?v=dQw4w9WgXcQ", VideoMetadata()),
        )

        assertEquals("Cargando titulo...", analysis.title)
        assertTrue(analysis.thumbnailUrl?.contains("dQw4w9WgXcQ") == true)
    }

    @Test
    fun buildsOptionsFromReportedFormats() {
        val analysis = assertNotNull(
            analyzeLink(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                VideoMetadata(
                    durationSeconds = 213,
                    formats = listOf(
                        VideoFormatInfo(
                            height = 144,
                            extension = "mp4",
                            videoCodec = "avc1.4d400c",
                            totalBitrateKbps = 78,
                        ),
                        VideoFormatInfo(
                            height = 360,
                            extension = "mp4",
                            videoCodec = "avc1.4d401e",
                            audioCodec = "mp4a.40.2",
                            totalBitrateKbps = 400,
                        ),
                        VideoFormatInfo(
                            height = 720,
                            extension = "webm",
                            videoCodec = "vp9",
                            totalBitrateKbps = 2500,
                        ),
                        VideoFormatInfo(
                            height = 720,
                            extension = "mp4",
                            videoCodec = "avc1.64001f",
                            totalBitrateKbps = 2000,
                        ),
                        VideoFormatInfo(
                            height = null,
                            extension = "m4a",
                            audioCodec = "mp4a.40.2",
                            totalBitrateKbps = 128,
                        ),
                        // Storyboards arrive as formats without codecs and must be ignored.
                        VideoFormatInfo(height = 27, extension = "mhtml"),
                    ),
                ),
            ),
        )

        assertEquals(listOf("720p", "360p", "144p"), analysis.videoOptions.map { it.label })
        assertEquals("WEBM · VP9", analysis.videoOptions.first().format)
        assertEquals("HD", analysis.videoOptions.first().quality)
        assertTrue(analysis.videoOptions.first { it.recommended }.label == "720p")
        // 720p is video-only: 2500 kbps + 128 kbps of audio over 213 s.
        assertEquals("68 MB", analysis.videoOptions.first().size)
    }

    @Test
    fun fallsBackToStaticOptionsWithoutFormats() {
        val analysis = assertNotNull(
            analyzeLink("https://example.com/video", VideoMetadata(title = "Solo título")),
        )

        assertEquals(6, analysis.videoOptions.size)
        assertEquals("1080p", analysis.videoOptions.first { it.recommended }.label)
    }
}
