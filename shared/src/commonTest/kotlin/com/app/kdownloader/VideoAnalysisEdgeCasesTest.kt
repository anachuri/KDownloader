package com.app.kdownloader

import com.app.kdownloader.model.analyzeLink
import com.app.kdownloader.platform.VideoFormatInfo
import com.app.kdownloader.platform.VideoMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Edge cases that the happy-path [VideoAnalysisTest] does not cover.
 *
 * These tests intentionally describe the behaviour the KDoc promises (real duration, one option
 * per real stream, nothing fabricated), not just whatever the implementation happens to do.
 */
class VideoAnalysisEdgeCasesTest {

    private val youtubeUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"

    private fun analysis(
        url: String = youtubeUrl,
        title: String? = null,
        durationSeconds: Long? = 213,
        thumbnailUrl: String? = null,
        formats: List<VideoFormatInfo> = emptyList(),
    ) = analyzeLink(
        url,
        VideoMetadata(
            title = title,
            durationSeconds = durationSeconds,
            thumbnailUrl = thumbnailUrl,
            formats = formats,
        ),
    )

    private fun video(
        height: Int?,
        codec: String? = "avc1",
        width: Int? = null,
        extension: String? = "mp4",
        tbr: Int? = null,
        filesize: Long? = null,
        audioCodec: String? = null,
    ) = VideoFormatInfo(
        height = height,
        width = width,
        extension = extension,
        videoCodec = codec,
        audioCodec = audioCodec,
        totalBitrateKbps = tbr,
        fileSizeBytes = filesize,
    )

    private fun audio(tbr: Int? = 128) = VideoFormatInfo(
        extension = "m4a",
        audioCodec = "mp4a.40.2",
        totalBitrateKbps = tbr,
    )

    // ---------------------------------------------------------------------------------------
    // URL / platform detection
    // ---------------------------------------------------------------------------------------

    @Test
    fun acceptsSchemeInAnyCaseAndSurroundingSpaces() {
        val analysis = assertNotNull(analyzeLink("  HTTPS://WWW.YouTube.COM/watch?v=dQw4w9WgXcQ  "))

        assertEquals("YouTube", analysis.platform)
        assertEquals("youtube.com", analysis.host)
        assertEquals(youtubeUrl, analysis.url)
        assertTrue(analysis.thumbnailUrl?.contains("dQw4w9WgXcQ") == true)
    }

    @Test
    fun detectsEverySupportedPlatform() {
        val cases = mapOf(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ" to "YouTube",
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ" to "YouTube",
            "https://youtu.be/dQw4w9WgXcQ" to "YouTube",
            "https://vimeo.com/76979871" to "Vimeo",
            "https://www.tiktok.com/@user/video/123" to "TikTok",
            "https://www.instagram.com/reel/abc/" to "Instagram",
            "https://www.facebook.com/watch/?v=123" to "Facebook",
            "https://fb.watch/abc/" to "Facebook",
            "https://x.com/user/status/123" to "X",
            "https://twitter.com/user/status/123" to "X",
            "https://www.dailymotion.com/video/x123" to "Dailymotion",
            "https://dai.ly/x123" to "Dailymotion",
            "https://cdn.example.com/uploads/photo.jpeg?w=800" to "Imagen",
        )

        cases.forEach { (url, expected) ->
            assertEquals(expected, analyzeLink(url)?.platform, "wrong platform for $url")
        }
    }

    @Test
    fun unknownHostsUseTheFirstDomainLabel() {
        val analysis = assertNotNull(analyzeLink("https://media.example.org/clip.mp4"))

        assertEquals("Media", analysis.platform)
        assertEquals("Contenido de Media", analysis.title)
        assertNull(analysis.thumbnailUrl)
    }

    /**
     * `platformName` matches suffixes, so any host ending in `x.com` is currently labelled "X"
     * even though it is unrelated to the social network.
     */
    @Test
    fun hostsEndingInXComAreNotMistakenForTheSocialNetwork() {
        assertEquals("Dropbox", analyzeLink("https://www.dropbox.com/s/abc/clip.mp4")?.platform)
        assertEquals("Netflix", analyzeLink("https://www.netflix.com/watch/123")?.platform)
    }

    @Test
    fun rejectsLinksWithoutAHost() {
        assertNull(analyzeLink("https://"))
        assertNull(analyzeLink("https:///watch?v=abc"))
        assertNull(analyzeLink("https://example"))
        assertNull(analyzeLink("   "))
        assertNull(analyzeLink("www.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun buildsYoutubeThumbnailsForEveryShortLinkForm() {
        val cases = listOf(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s",
            "https://youtu.be/dQw4w9WgXcQ?si=token",
            "https://www.youtube.com/shorts/dQw4w9WgXcQ",
            "https://www.youtube.com/embed/dQw4w9WgXcQ",
            "https://www.youtube.com/live/dQw4w9WgXcQ",
        )

        cases.forEach { url ->
            assertEquals(
                "https://img.youtube.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
                analyzeLink(url)?.thumbnailUrl,
                "wrong thumbnail for $url",
            )
        }
    }

    @Test
    fun buildsVimeoAndDirectImageThumbnails() {
        assertEquals(
            "https://vumbnail.com/76979871.jpg",
            analyzeLink("https://vimeo.com/video/76979871")?.thumbnailUrl,
        )
        assertEquals(
            "https://cdn.example.com/photo.JPG?w=800",
            analyzeLink("https://cdn.example.com/photo.JPG?w=800")?.thumbnailUrl,
        )
        assertNull(analyzeLink("https://example.com/video.mp4")?.thumbnailUrl)
    }

    // ---------------------------------------------------------------------------------------
    // Duration / title / audio
    // ---------------------------------------------------------------------------------------

    @Test
    fun formatsDurationAcrossBoundaries() {
        val cases = mapOf(
            1L to "0:01",
            59L to "0:59",
            60L to "1:00",
            61L to "1:01",
            3599L to "59:59",
            3600L to "1:00:00",
            3661L to "1:01:01",
            86399L to "23:59:59",
        )

        cases.forEach { (seconds, expected) ->
            assertEquals(expected, analysis(durationSeconds = seconds)?.duration, "wrong for $seconds s")
        }
    }

    @Test
    fun unknownOrInvalidDurationShowsDashesEverywhere() {
        listOf(null, 0L, -5L).forEach { seconds ->
            val analysis = assertNotNull(analysis(durationSeconds = seconds))

            assertEquals("—", analysis.duration, "duration for $seconds")
            assertEquals(listOf("—", "—", "—", "—"), analysis.audioOptions.map { it.size })
        }
    }

    @Test
    fun blankTitlesFallBackToThePlatformPlaceholder() {
        assertEquals("Cargando titulo...", analysis(title = "   ")?.title)
        assertEquals("Cargando titulo...", analysis(title = "")?.title)
        assertEquals("Mi vídeo", analysis(title = "Mi vídeo")?.title)
    }

    @Test
    fun audioOptionsMatchTheDocumentedCatalog() {
        val analysis = assertNotNull(analysis())

        assertEquals(
            listOf("MP3 · 320 kbps", "M4A · 256 kbps", "Opus · 160 kbps", "MP3 · 128 kbps"),
            analysis.audioOptions.map { it.label },
        )
        assertEquals(listOf("8 MB", "6 MB", "4 MB", "3 MB"), analysis.audioOptions.map { it.size })
    }

    // ---------------------------------------------------------------------------------------
    // Dynamic video options
    // ---------------------------------------------------------------------------------------

    @Test
    fun keepsTheBestStreamPerHeightAndIgnoresAudioOnlyStreams() {
        val analysis = assertNotNull(
            analysis(
                formats = listOf(
                    video(720, codec = "vp9", extension = "webm", tbr = 2500),
                    video(720, codec = "avc1", tbr = 2000, audioCodec = "mp4a"),
                    video(360, codec = "avc1", tbr = 400, audioCodec = "mp4a"),
                    audio(tbr = 128),
                ),
            ),
        )

        assertEquals(listOf("720p", "360p"), analysis.videoOptions.map { it.label })
        // 720p is video-only here, so the best audio-only bitrate (128) is added: 68 MB.
        assertEquals("WEBM · VP9", analysis.videoOptions.first().format)
        assertEquals("68 MB", analysis.videoOptions.first().size)
        assertEquals("360p", analysis.videoOptions.last().label)
        assertEquals("10 MB", analysis.videoOptions.last().size)
    }

    @Test
    fun recommendsExactlyOneOptionAndPrefers1080p() {
        val with1080 = assertNotNull(
            analysis(formats = listOf(video(2160, tbr = 40_000), video(1080, tbr = 12_000), video(720, tbr = 6_000))),
        )

        assertEquals(1, with1080.videoOptions.count { it.recommended })
        assertEquals("1080p", with1080.videoOptions.single { it.recommended }.label)
    }

    @Test
    fun recommendsTheClosestOptionBelow1080p() {
        val analysis = assertNotNull(
            analysis(formats = listOf(video(1440, tbr = 25_000), video(720, tbr = 6_000), video(480, tbr = 3_000))),
        )

        assertEquals(1, analysis.videoOptions.count { it.recommended })
        assertEquals("720p", analysis.videoOptions.single { it.recommended }.label)
    }

    @Test
    fun capsTheOptionListAtEightHighestHeights() {
        val heights = listOf(144, 240, 360, 480, 720, 1080, 1440, 2160, 2880, 4320)
        val analysis = assertNotNull(
            analysis(formats = heights.map { video(it, tbr = it * 10) }),
        )

        assertEquals(
            listOf("4320p", "2880p", "2160p", "1440p", "1080p", "720p", "480p", "360p"),
            analysis.videoOptions.map { it.label },
        )
        assertEquals(1, analysis.videoOptions.count { it.recommended })
        assertEquals("1080p", analysis.videoOptions.single { it.recommended }.label)
    }

    @Test
    fun mapsQualityTiersToTheirHeights() {
        val analysis = assertNotNull(
            analysis(formats = listOf(2160, 1440, 1080, 720, 480, 360).map { video(it, tbr = it * 10) }),
        )

        assertEquals(
            listOf("4K", "2K", "Full HD", "HD", "SD", null),
            analysis.videoOptions.map { it.quality },
        )
    }

    @Test
    fun translatesKnownCodecsAndKeepsUnknownOnesUpperCased() {
        val formats = listOf(
            video(2160, codec = "avc1.640028"),
            video(1440, codec = "vp09.00.51.08", extension = "webm"),
            video(1080, codec = "av01.0.08M.08"),
            video(720, codec = "hev1.1.6.L93.B0"),
            video(480, codec = "vp8", extension = "webm"),
            video(360, codec = "theora", extension = "ogg"),
            video(240, codec = "h264", extension = null),
        )
        val analysis = assertNotNull(analysis(formats = formats))

        assertEquals(
            listOf(
                "MP4 · H.264",
                "WEBM · VP9",
                "MP4 · AV1",
                "MP4 · HEVC",
                "WEBM · VP8",
                "OGG · THEORA",
                "H.264",
            ),
            analysis.videoOptions.map { it.format },
        )
    }

    @Test
    fun estimatesSizeFromFileSizeWhenBitrateIsMissing() {
        val analysis = assertNotNull(
            analysis(
                durationSeconds = 100,
                formats = listOf(video(360, tbr = null, filesize = 8_000_000, audioCodec = "mp4a")),
            ),
        )

        // 8 MB over 100 s = 640 kbps, already including audio because the stream is muxed.
        assertEquals("7 MB", analysis.videoOptions.single().size)
    }

    @Test
    fun estimatesSizeFromHeightWhenNeitherBitrateNorFileSizeExist() {
        val analysis = assertNotNull(
            analysis(
                durationSeconds = 60,
                formats = listOf(video(720, tbr = null, filesize = null)),
            ),
        )

        // Estimated 6000 kbps for 720p + 128 kbps of default audio over 60 s.
        assertEquals("44 MB", analysis.videoOptions.single().size)
    }

    @Test
    fun rendersLargeSizesInGigabytes() {
        val analysis = assertNotNull(
            analysis(
                durationSeconds = 7200,
                formats = listOf(video(1080, tbr = 12_000)),
            ),
        )

        assertEquals("10,4 GB", analysis.videoOptions.single().size)
    }

    @Test
    fun ignoresStoryboardAndAudioOnlyMetadata() {
        val storyboardsOnly = assertNotNull(
            analysis(
                formats = listOf(
                    VideoFormatInfo(height = 90, extension = "mhtml"),
                    audio(tbr = 128),
                ),
            ),
        )
        val best = storyboardsOnly.videoOptions.single()
        assertEquals("Mejor calidad", best.label)
        assertNull(best.maxHeight)
        assertTrue(best.recommended)

        val audioOnly = assertNotNull(analysis(formats = listOf(audio(tbr = 128))))
        assertEquals(listOf("Mejor calidad"), audioOnly.videoOptions.map { it.label })
    }

    @Test
    fun portraitOptionsKeepTheRealHeightForTheDownloadSelector() {
        val analysis = assertNotNull(
            analysis(
                formats = listOf(
                    video(1280, width = 720, tbr = 947),
                    video(640, width = 360, tbr = 196),
                ),
            ),
        )

        assertEquals(listOf("720p", "360p"), analysis.videoOptions.map { it.label })
        assertEquals(1280, analysis.videoOptions.first().maxHeight)
        assertEquals(640, analysis.videoOptions.last().maxHeight)
    }

    @Test
    fun landscapeWidthNeverOverridesTheHeight() {
        val analysis = assertNotNull(
            analysis(formats = listOf(video(1080, width = 1920, tbr = 5_000))),
        )

        assertEquals("1080p", analysis.videoOptions.single().label)
        assertEquals(1080, analysis.videoOptions.single().maxHeight)
    }

    @Test
    fun formatsWithoutCodecsOrHeightAreNotTurnedIntoOptions() {
        val analysis = assertNotNull(
            analysis(
                formats = listOf(
                    VideoFormatInfo(height = 720, extension = "mp4", videoCodec = null, audioCodec = null),
                    VideoFormatInfo(height = null, extension = "mp4", videoCodec = "avc1"),
                ),
            ),
        )

        assertEquals(listOf("Mejor calidad"), analysis.videoOptions.map { it.label })
    }
}
