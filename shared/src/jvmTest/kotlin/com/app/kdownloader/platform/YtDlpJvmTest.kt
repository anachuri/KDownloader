package com.app.kdownloader.platform

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun pointsYtDlpAtTheBundledFfmpeg() {
        val ffmpeg = "/opt/kdownloader/ffmpeg"
        val args = buildYtDlpArgs("yt-dlp", base, ffmpegAvailable = true, ffmpegLocation = ffmpeg)

        assertEquals(ffmpeg, args.valueAfter("--ffmpeg-location"))
    }

    @Test
    fun omitsFfmpegLocationWithoutFfmpeg() {
        val args = buildYtDlpArgs("yt-dlp", base, ffmpegAvailable = false, ffmpegLocation = null)

        assertTrue(args.none { it == "--ffmpeg-location" })
    }

    @Test
    fun picksTheYtDlpAssetForEachPlatform() {
        assertEquals("yt-dlp.exe", ytDlpAssetName("Windows 11", "amd64"))
        assertEquals("yt-dlp_arm64.exe", ytDlpAssetName("Windows 11", "aarch64"))
        assertEquals("yt-dlp_macos", ytDlpAssetName("Mac OS X", "x86_64"))
        assertEquals("yt-dlp_linux", ytDlpAssetName("Linux", "amd64"))
        assertEquals("yt-dlp_linux_aarch64", ytDlpAssetName("Linux", "aarch64"))
        assertNull(ytDlpAssetName("FreeBSD", "amd64"))
    }

    @Test
    fun comparesYtDlpVersions() {
        assertTrue(isNewerYtDlpVersion("2026.10.26", "2025.11.12"))
        assertTrue(isNewerYtDlpVersion("2026.10.26.1", "2026.10.26"))
        assertFalse(isNewerYtDlpVersion("2026.10.26", "2026.10.26"))
        assertFalse(isNewerYtDlpVersion("2025.11.12", "2026.10.26"))
    }

    @Test
    fun boundsOutputFileNameToAvoidEnametoolong() {
        val args = buildYtDlpArgs("yt-dlp", base, ffmpegAvailable = true)

        // Both placeholders are byte-truncated: site titles/ids can otherwise exceed the 255-byte
        // limit of a single path component ("File name too long").
        assertEquals("%(title).100B [%(id).24B].%(ext)s", args.valueAfter("-o"))
    }

    @Test
    fun metadataFallbackUsesExplicitClients() {
        val fallback = metadataOptions(YOUTUBE_FALLBACK_CLIENT_ARGS)
        assertEquals(YOUTUBE_FALLBACK_CLIENT_ARGS, fallback.valueAfter("--extractor-args"))
        assertTrue(YOUTUBE_FALLBACK_CLIENT_ARGS.contains("visionos"))
        assertTrue(!YOUTUBE_FALLBACK_CLIENT_ARGS.contains("-tv"))
    }

    @Test
    fun excludesYoutubeTvClients() {
        val expected = "youtube:player_client=default,-tv,-tv_samsung,-tv_downgraded"

        val metadata = metadataOptions()
        assertEquals(expected, metadata.valueAfter("--extractor-args"))
        assertTrue(metadata.contains("--ignore-no-formats-error"))

        val download = buildYtDlpArgs("yt-dlp", base, ffmpegAvailable = true)
        assertEquals(expected, download.valueAfter("--extractor-args"))
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
    fun explainsFileNameTooLong() {
        val message = describeYtDlpFailure(
            listOf(
                "ERROR: unable to open for writing: [Errno 36] File name too long: " +
                    "\"/storage/emulated/0/Android/data/com.app.kdownloader/files/" +
                    "Download/KDownloader/${"titulo".repeat(40)} [id].mp4\"",
            ),
            null,
        )

        assertTrue(message.contains("demasiado largo"), message)
        assertTrue(message.contains("File name too long"), message)
    }

    @Test
    fun retriesWithFallbackClientsWhenNoVideoStreams() = runBlocking {
        val calls = mutableListOf<List<String>>()
        val storyboardOnly = """
            ${METADATA_PREFIX}Video de prueba${'\u001F'}60${'\u001F'}https://img/thumb.jpg
            ${FORMATS_PREFIX}[{"format_id":"sb0","vcodec":"none","acodec":"none","height":90}]
        """.trimIndent()
        val withVideo = """
            ${FORMATS_PREFIX}[{"format_id":"137","vcodec":"avc1","acodec":"none","height":1080,"tbr":5000,"ext":"mp4"}]
        """.trimIndent()

        val metadata = fetchMetadataWithFallback { args ->
            calls += args
            if (calls.size == 1) storyboardOnly else withVideo
        }

        assertEquals(2, calls.size)
        assertEquals(metadataOptions(), calls[0])
        assertEquals(YOUTUBE_FALLBACK_CLIENT_ARGS, calls[1].valueAfter("--extractor-args"))
        assertEquals("Video de prueba", metadata?.title)
        assertEquals(1080, metadata?.formats?.single { it.isVideo }?.height)
    }

    @Test
    fun doesNotRetryWhenTheFirstPassHasVideoStreams() = runBlocking {
        val calls = mutableListOf<List<String>>()

        val metadata = fetchMetadataWithFallback { args ->
            calls += args
            "${FORMATS_PREFIX}[{\"format_id\":\"22\",\"vcodec\":\"avc1\",\"acodec\":\"mp4a\",\"height\":720}]"
        }

        assertEquals(1, calls.size)
        assertEquals(720, metadata?.formats?.single()?.height)
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
