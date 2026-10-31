package com.app.kdownloader.platform

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Retry policy shared by desktop and Android: a second pass with explicit YouTube clients only
 * happens when the first one produced no video stream.
 */
class MetadataFallbackTest {

    private val primaryVideo: String =
        "${METADATA_PREFIX}Primary\u001F213\u001Fhttps://img/p.jpg\n" +
            "$FORMATS_PREFIX[{\"height\":720,\"vcodec\":\"avc1\",\"acodec\":\"none\",\"tbr\":2500}]"

    private val audioOnly: String =
        "${METADATA_PREFIX}Audio only\u001F60\u001FNA\n" +
            "$FORMATS_PREFIX[{\"vcodec\":\"none\",\"acodec\":\"mp4a.40.2\",\"tbr\":128}]"

    private val storyboards: String =
        "${METADATA_PREFIX}Storyboards\u001F60\u001FNA\n" +
            "$FORMATS_PREFIX[{\"vcodec\":\"none\",\"acodec\":\"none\",\"height\":90}]"

    @Test
    fun returnsNullWhenBothPassesFail() = runBlocking {
        var calls = 0

        val metadata = fetchMetadataWithFallback { calls++; null }

        assertNull(metadata)
        assertEquals(2, calls, "the fallback pass should still be attempted")
    }

    @Test
    fun returnsTheFallbackWhenThePrimaryPassFails() = runBlocking {
        val calls = mutableListOf<List<String>>()

        val metadata = fetchMetadataWithFallback { args ->
            calls += args
            if (calls.size == 1) null else primaryVideo
        }

        assertEquals(2, calls.size)
        assertEquals(metadataOptions(), calls[0])
        assertEquals(YOUTUBE_FALLBACK_CLIENT_ARGS, calls[1].valueAfter("--extractor-args"))
        assertEquals("Primary", assertNotNull(metadata).title)
        assertEquals(720, metadata.formats.single().height)
    }

    @Test
    fun keepsThePrimaryResultWhenNeitherPassHasVideo() = runBlocking {
        val metadata = fetchMetadataWithFallback { if (it == metadataOptions()) audioOnly else storyboards }

        assertEquals("Audio only", assertNotNull(metadata).title)
        assertEquals(1, metadata.formats.size)
        assertNull(metadata.formats.single().height, "audio-only primary formats must be kept")
    }

    @Test
    fun keepsThePrimaryResultWhenTheRetryFails() = runBlocking {
        val metadata = fetchMetadataWithFallback { if (it == metadataOptions()) audioOnly else null }

        assertEquals("Audio only", assertNotNull(metadata).title)
    }

    @Test
    fun mergesPrimaryFieldsMissingFromTheRetry() = runBlocking {
        val metadata = assertNotNull(
            fetchMetadataWithFallback {
                if (it == metadataOptions()) storyboards else
                    "$FORMATS_PREFIX[{\"height\":1080,\"vcodec\":\"avc1\",\"acodec\":\"none\",\"tbr\":5000}]"
            },
        )

        // The retry only contributed the streams; title/duration survive from the first pass.
        assertEquals("Storyboards", metadata.title)
        assertEquals(60L, metadata.durationSeconds)
        assertEquals(1080, metadata.formats.single().height)
    }

    @Test
    fun retriesWhenTheFirstPassOnlyReportsStoryboards() = runBlocking {
        var calls = 0

        val metadata = fetchMetadataWithFallback { calls++; if (calls == 1) storyboards else primaryVideo }

        assertEquals(2, calls)
        assertEquals(720, assertNotNull(metadata).formats.single().height)
    }

    @Test
    fun doesNotRetryWithVideoStreamsOnTheFirstPass() = runBlocking {
        var calls = 0

        val metadata = fetchMetadataWithFallback { calls++; primaryVideo }

        assertEquals(1, calls)
        assertEquals("Primary", assertNotNull(metadata).title)
    }

    private fun List<String>.valueAfter(flag: String): String {
        val index = indexOf(flag)
        assertTrue(index >= 0 && index < lastIndex, "missing flag $flag in $this")
        return get(index + 1)
    }
}
