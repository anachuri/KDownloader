package com.app.kdownloader.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YtDlpJvmTest {

    private val base = DownloadRequest(
        url = "https://example.com/watch?v=abc",
        outputDirectory = "build/tmp/downloads",
    )

    private fun List<String>.valueAfter(flag: String): String {
        val index = indexOf(flag)
        assertTrue(index >= 0 && index < lastIndex, "missing flag $flag in $this")
        return get(index + 1)
    }

    @Test
    fun videoWithFfmpegRequestsMergedStreams() {
        val args = buildYtDlpArgs("yt-dlp", base.copy(maxHeight = 1080), ffmpegAvailable = true)

        assertEquals("bv*[height<=1080]+ba/b[height<=1080]/b", args.valueAfter("--format"))
        assertTrue(args.contains("--progress-template"))
        assertEquals("https://example.com/watch?v=abc", args.last())
        assertTrue(args.valueAfter("--paths").endsWith("downloads"))
        assertTrue(args.any { it.startsWith("after_move:${FILE_PREFIX}") })
    }

    @Test
    fun videoWithoutFfmpegFallsBackToCombinedFile() {
        val args = buildYtDlpArgs("yt-dlp", base.copy(maxHeight = 720), ffmpegAvailable = false)

        assertEquals("b[height<=720]/b", args.valueAfter("--format"))
    }

    @Test
    fun audioWithFfmpegExtractsAndConverts() {
        val args = buildYtDlpArgs(
            "yt-dlp",
            base.copy(audioFormat = "mp3", audioBitrateKbps = 320),
            ffmpegAvailable = true,
        )

        assertTrue(args.contains("--extract-audio"))
        assertEquals("mp3", args.valueAfter("--audio-format"))
        assertEquals("320K", args.valueAfter("--audio-quality"))
        assertTrue(args.none { it == "--format" })
    }

    @Test
    fun audioWithoutFfmpegKeepsOriginalStream() {
        val args = buildYtDlpArgs(
            "yt-dlp",
            base.copy(audioFormat = "mp3", audioBitrateKbps = 128),
            ffmpegAvailable = false,
        )

        assertTrue(args.none { it == "--extract-audio" })
        assertEquals("bestaudio/best", args.valueAfter("--format"))
    }

    @Test
    fun parsesProgressLine() {
        val progress = assertNotNull(
            parseProgressLine("${PROGRESS_PREFIX}1024|2848208|NA| 1.1%|293.51KiB/s|00:09"),
        )

        assertEquals(1024L, progress.downloadedBytes)
        assertEquals(2848208L, progress.totalBytes)
        assertEquals(0.011f, assertNotNull(progress.fraction), 0.0005f)
        assertEquals("293.51KiB/s", progress.speed)
        assertEquals("00:09", progress.eta)
    }

    @Test
    fun computesFractionWhenPercentIsUnknown() {
        val progress = assertNotNull(parseProgressLine("${PROGRESS_PREFIX}500000|1000000|NA|NA|NA|NA"))

        assertEquals(0.5f, assertNotNull(progress.fraction), 0.0005f)
        assertNull(progress.speed)
        assertNull(progress.eta)
    }

    @Test
    fun ignoresNonProgressLines() {
        assertNull(parseProgressLine("[download] Destination: video.mp4"))
        assertNull(parseProgressLine("${FILE_PREFIX}/tmp/video.mp4"))
        assertNull(parseProgressLine("${PROGRESS_PREFIX}incomplete"))
    }
}
