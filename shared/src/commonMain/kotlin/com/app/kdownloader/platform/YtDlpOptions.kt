package com.app.kdownloader.platform

internal const val PROGRESS_PREFIX = "KDLPROGRESS|"
internal const val FILE_PREFIX = "KDLFILE|"
internal const val METADATA_PREFIX = "KDLMETA|"

/** `%`-template fields are separated with the ASCII unit separator, which titles never contain. */
private const val METADATA_SEPARATOR = "\u001F"

private const val METADATA_TEMPLATE =
    "$METADATA_PREFIX%(title)s$METADATA_SEPARATOR%(duration)s$METADATA_SEPARATOR%(thumbnail)s"

private const val PROGRESS_TEMPLATE =
    "download:$PROGRESS_PREFIX%(progress.downloaded_bytes)s|%(progress.total_bytes)s|" +
        "%(progress.total_bytes_estimate)s|%(progress._percent_str)s|%(progress._speed_str)s|" +
        "%(progress._eta_str)s"

/**
 * Builds the yt-dlp arguments shared by every platform implementation.
 *
 * The URL is intentionally *not* included: the desktop implementation appends it as the last
 * argument, while `youtubedl-android` owns the URL list of its `YoutubeDLRequest`.
 */
internal fun buildYtDlpOptions(
    request: DownloadRequest,
    ffmpegAvailable: Boolean,
): List<String> = buildList {
    add("--newline") // one line per progress tick instead of carriage-return updates
    add("--no-playlist")
    add("--no-warnings")
    add("--encoding")
    add("utf-8")
    // --print implies --quiet; --no-quiet keeps the progress stream flowing.
    add("--print")
    add("after_move:$FILE_PREFIX%(filepath)s")
    add("--no-quiet")
    add("--no-simulate")
    add("--progress-template")
    add(PROGRESS_TEMPLATE)
    add("--paths")
    add(request.outputDirectory)
    add("-o")
    add("%(title).120B [%(id)s].%(ext)s")

    val audioFormat = request.audioFormat
    if (audioFormat != null) {
        if (ffmpegAvailable) {
            add("--extract-audio")
            add("--audio-format")
            add(audioFormat)
            request.audioBitrateKbps?.let { add("--audio-quality"); add("${it}K") }
        } else {
            // Extraction/conversion needs ffmpeg; keep the original container instead of failing.
            add("--format")
            add("bestaudio/best")
        }
    } else {
        val height = request.maxHeight
        val selector = when {
            height == null && ffmpegAvailable -> "bv*+ba/b"
            height == null -> "b"
            ffmpegAvailable -> "bv*[height<=$height]+ba/b[height<=$height]/b"
            else -> "b[height<=$height]/b"
        }
        add("--format")
        add(selector)
    }
}

/**
 * Arguments for a metadata-only run.
 *
 * `--print` implies `--simulate`, so nothing is downloaded; the single printed line is parsed by
 * [parseMetadataLine]. Both desktop and Android share these options so they resolve the same
 * fields.
 */
internal fun metadataOptions(): List<String> = listOf(
    "--skip-download",
    "--no-playlist",
    "--no-warnings",
    "--encoding",
    "utf-8",
    "--print",
    METADATA_TEMPLATE,
)

/** Parses the `KDLMETA|…` line printed by [metadataOptions]. */
internal fun parseMetadataLine(line: String): VideoMetadata? {
    if (!line.startsWith(METADATA_PREFIX)) return null
    val parts = line.removePrefix(METADATA_PREFIX).split(METADATA_SEPARATOR)

    fun String.value(): String? = trim().takeUnless { it.isEmpty() || it == "NA" }

    val title = parts.getOrNull(0)?.value()
    val duration = parts.getOrNull(1)?.value()
        ?.toDoubleOrNull()
        ?.toLong()
        ?.takeIf { it > 0 }
    val thumbnail = parts.getOrNull(2)?.value()

    return if (title == null && duration == null && thumbnail == null) {
        null
    } else {
        VideoMetadata(title = title, durationSeconds = duration, thumbnailUrl = thumbnail)
    }
}

/** Parses a `KDLPROGRESS|…` line emitted by the `--progress-template` above. */
internal fun parseProgressLine(line: String): DownloadEvent.Progress? {
    if (!line.startsWith(PROGRESS_PREFIX)) return null
    val parts = line.removePrefix(PROGRESS_PREFIX).split('|')
    if (parts.size < 6) return null

    val downloaded = parts[0].trim().toLongOrNull()
    val total = parts[1].trim().toLongOrNull() ?: parts[2].trim().toLongOrNull()
    val usableTotal = total?.takeIf { it > 0 }
    val percent = parts[3].trim().removeSuffix("%").trim().toFloatOrNull()
        ?: downloaded?.let { bytes -> usableTotal?.let { bytes * 100f / it } }

    return DownloadEvent.Progress(
        fraction = percent?.let { (it / 100f).coerceIn(0f, 1f) },
        downloadedBytes = downloaded,
        totalBytes = usableTotal,
        speed = parts[4].trim().takeUnless { it.isUnavailable() },
        eta = parts[5].trim().takeUnless { it.isUnavailable() },
    )
}

private fun String.isUnavailable(): Boolean =
    isBlank() || this == "NA" || startsWith("Unknown", ignoreCase = true)
