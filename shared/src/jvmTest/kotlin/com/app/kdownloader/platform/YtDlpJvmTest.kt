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

    @Test
    fun parsesMetadataLine() {
        val metadata = assertNotNull(
            parseMetadataLine(
                "${METADATA_PREFIX}Never Gonna Give You Up\u001F213.4\u001Fhttps://i.ytimg.com/vi/abc/hq.jpg",
            ),
        )

        assertEquals("Never Gonna Give You Up", metadata.title)
        assertEquals(213L, metadata.durationSeconds)
        assertEquals("https://i.ytimg.com/vi/abc/hq.jpg", metadata.thumbnailUrl)
    }

    @Test
    fun toleratesMissingMetadataFields() {
        val metadata = assertNotNull(
            parseMetadataLine("${METADATA_PREFIX}Only a title\u001FNA\u001FNA"),
        )

        assertEquals("Only a title", metadata.title)
        assertNull(metadata.durationSeconds)
        assertNull(metadata.thumbnailUrl)
    }

    @Test
    fun rejectsMetadataWithoutUsefulValues() {
        assertNull(parseMetadataLine("${METADATA_PREFIX}NA\u001FNA\u001FNA"))
        assertNull(parseMetadataLine("not metadata"))
    }

    @Test
    fun parsesFormatsLine() {
        val json = """
            [
              {"height":1080,"width":1920,"fps":30,"ext":"mp4","vcodec":"avc1.640028","acodec":"none",
               "tbr":1041.4,"filesize":27712345,
               "http_headers":{"User-Agent":"x"},"fragments":[{"url":"https://example.com"}]},
              {"ext":"m4a","vcodec":"none","acodec":"mp4a.40.2","tbr":129.5,"filesize":3449447},
              {"height":27,"ext":"mhtml","vcodec":"none","acodec":"none"}
            ]
        """.trimIndent().replace("\n", "")

        val formats = parseFormatsLine("$FORMATS_PREFIX$json")

        assertEquals(2, formats.size)
        val video = formats.first()
        assertEquals(1080, video.height)
        assertEquals(1920, video.width)
        assertEquals(30, video.fps)
        assertEquals("mp4", video.extension)
        assertEquals("avc1.640028", video.videoCodec)
        assertNull(video.audioCodec)
        assertEquals(1041, video.totalBitrateKbps)
        assertEquals(27712345L, video.fileSizeBytes)

        val audio = formats.last()
        assertNull(audio.height)
        assertNull(audio.videoCodec)
        assertEquals("mp4a.40.2", audio.audioCodec)
    }

    @Test
    fun parsesFullMetadataOutput() {
        val output = listOf(
            "$METADATA_PREFIX" + "Title\u001F213\u001Fhttps://img.example/thumb.jpg",
            "$FORMATS_PREFIX" + """[{"height":720,"ext":"mp4","vcodec":"avc1","acodec":"none","tbr":600}]""",
        ).joinToString("\n")

        val metadata = assertNotNull(parseMetadataOutput(output))

        assertEquals("Title", metadata.title)
        assertEquals(213L, metadata.durationSeconds)
        assertEquals(1, metadata.formats.size)
        assertEquals(720, metadata.formats.single().height)
    }

    @Test
    fun toleratesMalformedFormatsJson() {
        assertTrue(parseFormatsLine("${FORMATS_PREFIX}not json").isEmpty())
        assertTrue(parseFormatsLine("something else").isEmpty())
        assertNull(parseMetadataOutput("no usable output"))
    }

    @Test
    fun explainsForbiddenDownloads() {
        val message = describeYtDlpFailure(
            listOf("ERROR: unable to download video data: HTTP Error 403: Forbidden"),
            null,
        )

        assertTrue(message.contains("403"))
        assertTrue(message.contains("desactualizado"))
        assertTrue(message.contains("HTTP Error 403"), "the raw line must stay visible: $message")
    }

    @Test
    fun explainsBrokenExtractors() {
        val message = describeYtDlpFailure(
            listOf("ERROR: [BiliBili] 16L411D7H5: Unable to extract initial state; please report this issue"),
            null,
        )

        assertTrue(message.contains("Actualiza yt-dlp"), message)
    }

    @Test
    fun keepsUnknownErrorsUnchanged() {
        assertEquals("ERROR: something odd", describeYtDlpFailure(listOf("ERROR: something odd"), null))
    }

    @Test
    fun fallsBackWhenNothingWasCaptured() {
        assertEquals(YT_DLP_UNKNOWN_FAILURE, describeYtDlpFailure(emptyList(), null))
        assertEquals("exit code 9", describeYtDlpFailure(emptyList(), "exit code 9"))
    }
}
