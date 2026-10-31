package com.app.kdownloader.ui

import com.app.kdownloader.model.AudioQuality
import com.app.kdownloader.platform.DownloadEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Pure formatting helpers rendered by the download card. */
class UiFormattingTest {

    @Test
    fun formatsBytesWithBinaryStepsAndSpanishDecimalComma() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("999 B", formatBytes(999))
        assertEquals("1,0 KB", formatBytes(1_000))
        assertEquals("1,5 KB", formatBytes(1_500))
        assertEquals("1,0 MB", formatBytes(1_000_000))
        assertEquals("1,5 MB", formatBytes(1_500_000))
        assertEquals("1,0 GB", formatBytes(1_000_000_000))
        assertEquals("1,3 GB", formatBytes(1_250_000_000))
    }

    @Test
    fun roundsToOneDecimalAwayFromBinaryNoise() {
        assertEquals("1,0", oneDecimal(1.04))
        assertEquals("1,1", oneDecimal(1.05))
        assertEquals("10,0", oneDecimal(9.99))
        assertEquals("0,0", oneDecimal(0.0))
    }

    @Test
    fun describesProgressWithPercentageSpeedAndEta() {
        assertEquals(
            "50% · 1.2MiB/s · ETA 00:09",
            describeProgress(
                DownloadEvent.Progress(
                    fraction = 0.5f,
                    speed = "1.2MiB/s",
                    eta = "00:09",
                ),
            ),
        )
    }

    @Test
    fun describesProgressWithoutATotalSize() {
        assertEquals(
            "1,5 KB",
            describeProgress(DownloadEvent.Progress(fraction = null, downloadedBytes = 1_500)),
        )
    }

    @Test
    fun fallsBackWhenThereIsNothingToReport() {
        assertEquals(
            "Descargando…",
            describeProgress(DownloadEvent.Progress(fraction = null)),
        )
    }

    @Test
    fun aKnownPercentageHidesTheRawByteCount() {
        assertEquals(
            "0%",
            describeProgress(DownloadEvent.Progress(fraction = 0f, downloadedBytes = 1_500)),
        )
        assertEquals("100%", describeProgress(DownloadEvent.Progress(fraction = 1f)))
        assertEquals("33%", describeProgress(DownloadEvent.Progress(fraction = 0.333f)))
    }

    @Test
    fun readsTheDownloadFormatFromTheAudioLabel() {
        assertEquals("mp3", audio("MP3 · 320 kbps").audioFormat())
        assertEquals("m4a", audio("M4A · 256 kbps").audioFormat())
        assertEquals("opus", audio("Opus · 160 kbps").audioFormat())
        assertEquals("mp3", audio("MP3 · 128 kbps").audioFormat())
        assertNull(audio("   · 128 kbps").audioFormat())
    }

    @Test
    fun readsTheBitrateFromTheAudioLabel() {
        assertEquals(320, audio("MP3 · 320 kbps").bitrateKbpsOrNull())
        assertEquals(256, audio("M4A · 256 kbps").bitrateKbpsOrNull())
        assertEquals(160, audio("Opus · 160 kbps").bitrateKbpsOrNull())
        assertEquals(128, audio("MP3 · 128 kbps").bitrateKbpsOrNull())
        assertEquals(256, audio("M4A · 256 KBPS").bitrateKbpsOrNull())
        assertNull(audio("MP3 · alta calidad").bitrateKbpsOrNull())
    }

    private fun audio(label: String) = AudioQuality(
        label = label,
        detail = "detalle",
        size = "—",
    )
}
