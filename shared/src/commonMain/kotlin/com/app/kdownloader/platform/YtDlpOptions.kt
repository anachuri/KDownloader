package com.app.kdownloader.platform

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlin.math.roundToInt

internal const val PROGRESS_PREFIX = "KDLPROGRESS|"
internal const val FILE_PREFIX = "KDLFILE|"
internal const val METADATA_PREFIX = "KDLMETA|"
internal const val FORMATS_PREFIX = "KDLFORMATS|"

/** `%`-template fields are separated with the ASCII unit separator, which titles never contain. */
private const val METADATA_SEPARATOR = "\u001F"

private const val METADATA_TEMPLATE =
    "$METADATA_PREFIX%(title)s$METADATA_SEPARATOR%(duration)s$METADATA_SEPARATOR%(thumbnail)s"

/**
 * `formats` is a JSON array, kept on a separate line so a title containing the separator cannot
 * corrupt it. It comes from the same extraction as the metadata (no extra network round trip).
 */
private const val FORMATS_TEMPLATE = "$FORMATS_PREFIX%(formats)j"

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
 * `--print` implies `--simulate`, so nothing is downloaded. Two lines are printed: the compact
 * metadata line parsed by [parseMetadataLine] and the formats JSON parsed by [parseFormatsLine].
 * Both desktop and Android share these options so they resolve the same fields.
 */
internal fun metadataOptions(): List<String> = listOf(
    "--skip-download",
    "--no-playlist",
    "--no-warnings",
    "--encoding",
    "utf-8",
    "--print",
    METADATA_TEMPLATE,
    "--print",
    FORMATS_TEMPLATE,
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

/**
 * Parses the `KDLFORMATS|…` line printed by [metadataOptions].
 *
 * Only the fields needed to describe the available resolutions are kept; malformed output (an
 * old yt-dlp release without `%(formats)j`, for instance) yields an empty list so the UI falls
 * back to the static demo list.
 */
internal fun parseFormatsLine(line: String): List<VideoFormatInfo> {
    if (!line.startsWith(FORMATS_PREFIX)) return emptyList()
    val payload = line.removePrefix(FORMATS_PREFIX)
    return runCatching {
        Json.parseToJsonElement(payload).jsonArray.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val videoCodec = obj.stringField("vcodec")?.takeUnless { it == "none" }
            val audioCodec = obj.stringField("acodec")?.takeUnless { it == "none" }
            // Storyboards and image formats have neither codec.
            if (videoCodec == null && audioCodec == null) return@mapNotNull null

            VideoFormatInfo(
                height = obj.doubleField("height")?.toInt()?.takeIf { it > 0 },
                width = obj.doubleField("width")?.toInt()?.takeIf { it > 0 },
                fps = obj.doubleField("fps")?.toInt()?.takeIf { it > 0 },
                extension = obj.stringField("ext"),
                videoCodec = videoCodec,
                audioCodec = audioCodec,
                totalBitrateKbps = obj.doubleField("tbr")?.roundToInt()?.takeIf { it > 0 },
                fileSizeBytes = (obj.doubleField("filesize") ?: obj.doubleField("filesize_approx"))
                    ?.toLong()
                    ?.takeIf { it > 0 },
            )
        }
    }.getOrDefault(emptyList())
}

/**
 * Combines every line printed by [metadataOptions] into a single [VideoMetadata].
 *
 * Returns `null` when yt-dlp printed nothing usable (extraction failed, unsupported site, ...).
 */
internal fun parseMetadataOutput(output: String): VideoMetadata? {
    var metadata: VideoMetadata? = null
    var formats: List<VideoFormatInfo> = emptyList()

    output.lineSequence().forEach { line ->
        parseMetadataLine(line)?.let { metadata = it }
        if (line.startsWith(FORMATS_PREFIX)) formats = parseFormatsLine(line)
    }

    val parsed = metadata
    val hasMetadata = parsed?.let {
        it.title != null || it.durationSeconds != null || it.thumbnailUrl != null
    } == true
    if (!hasMetadata && formats.isEmpty()) return null
    return (parsed ?: VideoMetadata()).copy(formats = formats)
}

private fun JsonObject.stringField(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject.doubleField(name: String): Double? =
    (this[name] as? JsonPrimitive)?.doubleOrNull

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
