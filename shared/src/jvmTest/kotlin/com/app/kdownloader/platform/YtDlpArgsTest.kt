package com.app.kdownloader.platform

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Desktop-specific argument assembly: the executable goes first, the URL last and relative
 * folders are resolved against the process working directory.
 */
class YtDlpArgsTest {

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
    fun putsTheExecutableFirstAndTheUrlLast() {
        val args = buildYtDlpArgs("yt-dlp", base, ffmpegAvailable = true)

        assertEquals("yt-dlp", args.first())
        assertEquals(base.url, args.last())
        assertEquals(1, args.count { it == base.url })
    }

    @Test
    fun resolvesRelativeOutputDirectoriesToAbsolutePaths() {
        val args = buildYtDlpArgs("yt-dlp", base, ffmpegAvailable = true)

        val paths = args.valueAfter("--paths")
        assertTrue(File(paths).isAbsolute, "--paths must be absolute, got $paths")
        assertTrue(paths.endsWith("downloads"), paths)
    }

    @Test
    fun keepsAbsoluteOutputDirectoriesUntouched() {
        val absolute = File("build/tmp/absolute-out").absolutePath
        val args = buildYtDlpArgs(
            "yt-dlp",
            base.copy(outputDirectory = absolute),
            ffmpegAvailable = true,
        )

        assertEquals(absolute, args.valueAfter("--paths"))
    }

    @Test
    fun bestVideoSelectorsCoverTheNoHeightCases() {
        assertEquals(
            "bv*+ba/b",
            buildYtDlpArgs("yt-dlp", base, ffmpegAvailable = true).valueAfter("--format"),
        )
        assertEquals(
            "b",
            buildYtDlpArgs("yt-dlp", base, ffmpegAvailable = false).valueAfter("--format"),
        )
    }

    @Test
    fun audioQualityIsOnlyAddedWhenAKnownBitrateIsRequested() {
        val withoutBitrate = buildYtDlpArgs(
            "yt-dlp",
            base.copy(audioFormat = "opus"),
            ffmpegAvailable = true,
        )
        assertTrue(withoutBitrate.none { it == "--audio-quality" })

        val withBitrate = buildYtDlpArgs(
            "yt-dlp",
            base.copy(audioFormat = "opus", audioBitrateKbps = 160),
            ffmpegAvailable = true,
        )
        assertEquals("160K", withBitrate.valueAfter("--audio-quality"))
    }

    @Test
    fun doesNotDuplicateSingleValueFlags() {
        val args = buildYtDlpArgs("yt-dlp", base.copy(maxHeight = 720), ffmpegAvailable = true)

        listOf("--paths", "-o", "--format", "--extractor-args", "--encoding", "--progress-template")
            .forEach { flag ->
                assertEquals(1, args.count { it == flag }, "$flag should appear once: $args")
            }
    }
}
