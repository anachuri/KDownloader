package com.app.kdownloader.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YtDlpErrorsTest {

    private fun message(vararg lines: String, fallback: String? = null) =
        describeYtDlpFailure(lines.toList(), fallback)

    @Test
    fun explainsForbiddenDownloads() {
        val raw = "ERROR: unable to download video data: HTTP Error 403: Forbidden"
        val text = message(raw)

        assertTrue(text.contains("403"), text)
        assertTrue(text.contains("desactualizado"), text)
        assertTrue(text.endsWith(raw), "the raw line must stay visible: $text")
    }

    @Test
    fun explainsBotChecks() {
        val text = message("ERROR: Sign in to confirm you're not a bot. Use --cookies")

        assertTrue(text.contains("bot", ignoreCase = true), text)
        assertTrue(text.contains("Reintenta"), text)
    }

    @Test
    fun explainsBrokenExtractors() {
        val text = message("ERROR: [BiliBili] x: Unable to extract initial state; please report this issue")

        assertTrue(text.contains("Actualiza yt-dlp"), text)
    }

    @Test
    fun explainsPrivateAndMembersOnlyVideos() {
        listOf(
            "ERROR: Private video. Sign in if you've been granted access",
            "ERROR: Join this channel to get access to members-only content",
            "ERROR: [youtube] x: members-only content",
        ).forEach { raw ->
            val text = message(raw)
            assertTrue(text.contains("privado") || text.contains("miembros"), "missing hint for: $raw")
        }
    }

    @Test
    fun explainsGeoRestrictedVideos() {
        val text = message("ERROR: This video is not available in your country")

        assertTrue(
            text.contains("región") || text.contains("bloqueado"),
            "a geo-restriction should be explained, got: $text",
        )
    }

    @Test
    fun explainsMissingFfmpeg() {
        val text = message("ERROR: ffmpeg not found. Please install or provide the path using --ffmpeg-location")

        assertTrue(text.contains("ffmpeg"), text)
        assertTrue(text.contains("Instálalo"), text)
    }

    @Test
    fun explainsUnavailableFormats() {
        val text = message("ERROR: Requested format is not available")

        assertTrue(text.contains("calidad solicitada"), text)
    }

    @Test
    fun explainsUnsupportedUrls() {
        val text = message("ERROR: Unsupported URL: https://example.com/file.zip")

        assertTrue(text.contains("no reconoce"), text)
    }

    @Test
    fun matchesHintsCaseInsensitively() {
        assertTrue(message("error: http error 403: forbidden").contains("403"))
        assertTrue(message("ERROR: PRIVATE VIDEO").contains("privado"))
    }

    @Test
    fun usesTheLastCapturedErrorLine() {
        val text = message(
            "ERROR: HTTP Error 403: Forbidden",
            "ERROR: something completely different",
        )

        assertEquals("ERROR: something completely different", text)
    }

    @Test
    fun keepsUnknownErrorsUnchanged() {
        assertEquals("ERROR: something odd", message("ERROR: something odd"))
    }

    @Test
    fun fallsBackToTheGivenDetail() {
        assertEquals("exit code 9", message(fallback = "exit code 9"))
        assertEquals("exit code 9", message(fallback = "  exit code 9  "))
    }

    @Test
    fun usesTheUnknownFailureWhenNothingWasCaptured() {
        assertEquals(YT_DLP_UNKNOWN_FAILURE, message())
        assertEquals(YT_DLP_UNKNOWN_FAILURE, message(fallback = "   "))
        assertEquals(YT_DLP_UNKNOWN_FAILURE, message(fallback = ""))
    }
}
