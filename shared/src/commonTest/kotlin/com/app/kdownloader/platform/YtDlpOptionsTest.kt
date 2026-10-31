package com.app.kdownloader.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cross-platform edge cases for the shared yt-dlp argument builder and output parsers.
 *
 * Everything here is pure code, so the same assertions run on JVM, Android host, JS and Wasm.
 */
class YtDlpOptionsTest {

    private val base = DownloadRequest(
        url = "https://example.com/watch?v=abc",
        outputDirectory = "build/tmp/downloads",
    )

    private fun List<String>.valueAfter(flag: String): String {
        val index = indexOf(flag)
        assertTrue(index >= 0 && index < lastIndex, "missing flag $flag in $this")
        return get(index + 1)
    }

    // ---------------------------------------------------------------------------------------
    // Arguments
    // ---------------------------------------------------------------------------------------

    @Test
    fun optionsDoNotContainTheUrl() {
        val args = buildYtDlpOptions(base, ffmpegAvailable = true)

        assertTrue(args.none { it == base.url }, "the URL is appended by each platform: $args")
    }

    @Test
    fun optionsAlwaysDisablePlaylistsAndKeepProgressAlive() {
        val args = buildYtDlpOptions(base, ffmpegAvailable = true)

        assertTrue(args.contains("--newline"))
        assertTrue(args.contains("--no-playlist"))
        assertTrue(args.contains("--no-simulate"))
        assertTrue(args.contains("--no-quiet")) // --print implies --quiet otherwise
        assertEquals("utf-8", args.valueAfter("--encoding"))
        assertEquals(base.outputDirectory, args.valueAfter("--paths"))
        assertEquals("%(title).100B [%(id).24B].%(ext)s", args.valueAfter("-o"))
        assertTrue(args.any { it.startsWith("after_move:$FILE_PREFIX") })
    }

    @Test
    fun bestQualitySelectorsDependOnFfmpeg() {
        assertEquals(
            "bv*+ba/b",
            buildYtDlpOptions(base, ffmpegAvailable = true).valueAfter("--format"),
        )
        assertEquals(
            "b",
            buildYtDlpOptions(base, ffmpegAvailable = false).valueAfter("--format"),
        )
    }

    @Test
    fun cappedSelectorsDependOnFfmpeg() {
        val capped = base.copy(maxHeight = 480)

        assertEquals(
            "bv*[height<=480]+ba/b[height<=480]/b",
            buildYtDlpOptions(capped, ffmpegAvailable = true).valueAfter("--format"),
        )
        assertEquals(
            "b[height<=480]/b",
            buildYtDlpOptions(capped, ffmpegAvailable = false).valueAfter("--format"),
        )
    }

    @Test
    fun audioWithoutABitrateOmitsTheQualityFlag() {
        val args = buildYtDlpOptions(base.copy(audioFormat = "m4a"), ffmpegAvailable = true)

        assertTrue(args.contains("--extract-audio"))
        assertEquals("m4a", args.valueAfter("--audio-format"))
        assertTrue(args.none { it == "--audio-quality" })
        assertTrue(args.none { it == "--format" })
    }

    @Test
    fun audioWinsOverAHeightCap() {
        val args = buildYtDlpOptions(
            base.copy(maxHeight = 1080, audioFormat = "mp3", audioBitrateKbps = 192),
            ffmpegAvailable = true,
        )

        assertTrue(args.contains("--extract-audio"))
        assertEquals("192K", args.valueAfter("--audio-quality"))
        assertTrue(args.none { it == "--format" })
    }

    @Test
    fun metadataOptionsAskForTwoPrintLinesAndNoDownload() {
        val args = metadataOptions()

        assertTrue(args.contains("--skip-download"))
        assertTrue(args.contains("--ignore-no-formats-error"))
        assertEquals(2, args.count { it == "--print" })

        val printed = args.withIndex()
            .filter { it.value == "--print" }
            .map { args[it.index + 1] }
        assertTrue(printed[0].startsWith(METADATA_PREFIX), "first print: ${printed[0]}")
        assertTrue(printed[1].startsWith(FORMATS_PREFIX), "second print: ${printed[1]}")
    }

    // ---------------------------------------------------------------------------------------
    // Progress parsing
    // ---------------------------------------------------------------------------------------

    @Test
    fun rejectsProgressLinesWithTooFewFields() {
        assertNull(parseProgressLine("$PROGRESS_PREFIX|1|2|3|4"))
        assertNull(parseProgressLine("${PROGRESS_PREFIX}not-a-progress-line"))
        assertNull(parseProgressLine("[download] 10% of 1MiB"))
    }

    @Test
    fun usesTheEstimatedTotalWhenTheRealSizeIsMissing() {
        val progress = assertNotNull(
            parseProgressLine("${PROGRESS_PREFIX}500|NA|1000|NA|NA|NA"),
        )

        assertEquals(1000L, progress.totalBytes)
        assertEquals(0.5f, assertNotNull(progress.fraction), 0.0005f)
    }

    @Test
    fun clampsOutOfRangePercentages() {
        assertEquals(
            1f,
            assertNotNull(parseProgressLine("${PROGRESS_PREFIX}2000|1000|NA|NA|NA|NA")?.fraction),
            0.0005f,
        )
        assertEquals(
            0f,
            assertNotNull(parseProgressLine("${PROGRESS_PREFIX}-5|NA|NA|-10%|NA|NA")?.fraction),
            0.0005f,
        )
    }

    @Test
    fun acceptsAnExactHundredPercent() {
        val progress = assertNotNull(parseProgressLine("${PROGRESS_PREFIX}1000|1000|NA|100.0%|NA|NA"))

        assertEquals(1f, assertNotNull(progress.fraction), 0.0005f)
    }

    @Test
    fun keepsZeroDownloadedBytesWhileStarting() {
        val progress = assertNotNull(parseProgressLine("${PROGRESS_PREFIX}0|1000|NA|0.0%|NA|NA"))

        assertEquals(0L, progress.downloadedBytes)
        assertEquals(0f, assertNotNull(progress.fraction), 0.0005f)
    }

    @Test
    fun treatsUnknownSpeedAndEtaAsAbsent() {
        val cases = listOf("NA", "", "   ", "Unknown", "Unknown B/s")

        cases.forEach { marker ->
            val progress = assertNotNull(
                parseProgressLine("${PROGRESS_PREFIX}1|2|NA|50%|$marker|$marker"),
                "missing progress for marker '$marker'",
            )
            assertNull(progress.speed, "speed for marker '$marker'")
            assertNull(progress.eta, "eta for marker '$marker'")
        }
    }

    @Test
    fun keepsSpeedAndEtaWhenPresent() {
        val progress = assertNotNull(
            parseProgressLine("${PROGRESS_PREFIX}1|2|NA|50%| 1.20MiB/s |01:23"),
        )

        assertEquals("1.20MiB/s", progress.speed)
        assertEquals("01:23", progress.eta)
    }

    // ---------------------------------------------------------------------------------------
    // Metadata lines
    // ---------------------------------------------------------------------------------------

    @Test
    fun keepsTitlesContainingPipesAndDashes() {
        val metadata = assertNotNull(
            parseMetadataLine("$METADATA_PREFIX  Artist | Song - Live  \u001F95\u001FNA"),
        )

        assertEquals("Artist | Song - Live", metadata.title)
        assertEquals(95L, metadata.durationSeconds)
        assertNull(metadata.thumbnailUrl)
    }

    @Test
    fun truncatesFractionalDurations() {
        assertEquals(213L, parseMetadataLine("$METADATA_PREFIX T\u001F213.9\u001FNA")?.durationSeconds)
        // 0.9 s truncates to zero, which is treated as "unknown" instead of showing 0:00.
        assertNull(parseMetadataLine("$METADATA_PREFIX T\u001F0.9\u001FNA")?.durationSeconds)
    }

    @Test
    fun ignoresZeroAndNegativeDurations() {
        assertNull(parseMetadataLine("$METADATA_PREFIX T\u001F0\u001FNA")?.durationSeconds)
        assertNull(parseMetadataLine("$METADATA_PREFIX T\u001F-5\u001FNA")?.durationSeconds)
        assertNull(parseMetadataLine("$METADATA_PREFIX T\u001Fnot-a-number\u001FNA")?.durationSeconds)
    }

    @Test
    fun keepsMetadataWhenOnlyOneFieldIsUsable() {
        val onlyThumbnail = assertNotNull(
            parseMetadataLine("$METADATA_PREFIX   \u001FNA\u001Fhttps://img/thumb.jpg"),
        )
        assertNull(onlyThumbnail.title)
        assertEquals("https://img/thumb.jpg", onlyThumbnail.thumbnailUrl)

        val onlyDuration = assertNotNull(parseMetadataLine("$METADATA_PREFIX NA\u001F60\u001FNA"))
        assertEquals(60L, onlyDuration.durationSeconds)
    }

    @Test
    fun rejectsMetadataLinesWithOnlyPlaceholders() {
        assertNull(parseMetadataLine("$METADATA_PREFIX NA\u001FNA\u001FNA"))
        assertNull(parseMetadataLine("$METADATA_PREFIX   \u001F   \u001F   "))
        assertNull(parseMetadataLine("not metadata at all"))
    }

    // ---------------------------------------------------------------------------------------
    // Formats parsing
    // ---------------------------------------------------------------------------------------

    @Test
    fun fallsBackToTheApproximateFileSize() {
        val json = """[{"height":720,"vcodec":"avc1","filesize":null,"filesize_approx":123456}]"""

        val format = parseFormatsLine("$FORMATS_PREFIX$json").single()

        assertEquals(123456L, format.fileSizeBytes)
    }

    @Test
    fun dropsUnknownNumericFieldsInsteadOfZeroes() {
        val json = """[
            {"height":0,"width":0,"fps":0,"vcodec":"avc1","tbr":0,"filesize":0},
            {"height":720,"width":1920,"vcodec":"avc1"}
        ]"""

        val formats = parseFormatsLine("$FORMATS_PREFIX$json")

        assertEquals(2, formats.size)
        val broken = formats.first()
        assertNull(broken.height)
        assertNull(broken.width)
        assertNull(broken.fps)
        assertNull(broken.totalBitrateKbps)
        assertNull(broken.fileSizeBytes)
        assertTrue(!broken.isVideo, "a format without height is not a video stream")
    }

    @Test
    fun skipsJsonEntriesThatCannotBeAStream() {
        val json = """[
            null,
            42,
            "text",
            {"vcodec":"none","acodec":"none","height":27},
            {"vcodec":"none","acodec":"none","height":90},
            {"height":1080,"vcodec":"avc1"}
        ]"""

        val formats = parseFormatsLine("$FORMATS_PREFIX$json")

        assertEquals(1, formats.size)
        assertEquals(1080, formats.single().height)
    }

    @Test
    fun rejectsJsonThatIsNotAnArray() {
        assertTrue(parseFormatsLine("$FORMATS_PREFIX{}").isEmpty())
        assertTrue(parseFormatsLine("${FORMATS_PREFIX}null").isEmpty())
        assertTrue(parseFormatsLine("$FORMATS_PREFIX").isEmpty())
    }

    // ---------------------------------------------------------------------------------------
    // Full output
    // ---------------------------------------------------------------------------------------

    @Test
    fun acceptsWindowsLineEndings() {
        val output = "$METADATA_PREFIX Title\u001F213\u001FNA\r\n" +
            "$FORMATS_PREFIX[{\"height\":720,\"vcodec\":\"avc1\"}]\r\n"

        val metadata = assertNotNull(parseMetadataOutput(output))

        assertEquals("Title", metadata.title)
        assertEquals(720, metadata.formats.single().height)
    }

    @Test
    fun returnsFormatsOnlyMetadataWhenTheTitleIsMissing() {
        val metadata = assertNotNull(
            parseMetadataOutput("$FORMATS_PREFIX[{\"height\":720,\"vcodec\":\"avc1\",\"acodec\":\"mp4a\"}]"),
        )

        assertNull(metadata.title)
        assertNull(metadata.durationSeconds)
        assertEquals(720, metadata.formats.single().height)
    }

    @Test
    fun theLastFormatsLineWins() {
        val output = listOf(
            "$FORMATS_PREFIX[{\"height\":360,\"vcodec\":\"avc1\"}]",
            "$FORMATS_PREFIX[{\"height\":1080,\"vcodec\":\"avc1\"}]",
        ).joinToString("\n")

        val metadata = assertNotNull(parseMetadataOutput(output))

        assertEquals(1080, metadata.formats.single().height)
    }

    @Test
    fun returnsNullWhenNothingUsableWasPrinted() {
        assertNull(parseMetadataOutput(""))
        assertNull(parseMetadataOutput("ERROR: Unsupported URL"))
        assertNull(parseMetadataOutput("$METADATA_PREFIX NA\u001FNA\u001FNA\n$FORMATS_PREFIX[]"))
    }

    @Test
    fun keepsMetadataEvenWhenTheFormatsLineIsBroken() {
        val metadata = assertNotNull(
            parseMetadataOutput("$METADATA_PREFIX Title\u001F60\u001FNA\n${FORMATS_PREFIX}not json"),
        )

        assertEquals("Title", metadata.title)
        assertTrue(metadata.formats.isEmpty())
    }
}
