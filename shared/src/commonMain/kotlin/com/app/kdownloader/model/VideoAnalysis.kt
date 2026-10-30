package com.app.kdownloader.model

import com.app.kdownloader.platform.VideoFormatInfo
import com.app.kdownloader.platform.VideoMetadata
import kotlin.math.roundToInt

data class VideoQuality(
    val label: String,
    val quality: String?,
    val format: String,
    val size: String,
    val recommended: Boolean = false,
    /**
     * Height yt-dlp must cap the download at. Portrait videos expose a large `height` (e.g.
     * 720x1280), while the label shows the short side (720p), so the download cannot rely on
     * parsing [label].
     */
    val maxHeight: Int? = null,
)

data class AudioQuality(
    val label: String,
    val detail: String,
    val size: String,
)

data class VideoAnalysis(
    val url: String,
    val platform: String,
    val host: String,
    val title: String,
    val duration: String,
    val thumbnailUrl: String?,
    val videoOptions: List<VideoQuality>,
    val audioOptions: List<AudioQuality>,
)

private data class AudioSpec(
    val label: String,
    val detail: String,
    val bitrateKbps: Int,
)

private val youtubeIdRegex = Regex(
    """(?:[?&]v=|youtu\.be/|shorts/|embed/|live/)([A-Za-z0-9_-]{11})""",
    RegexOption.IGNORE_CASE,
)
private val vimeoIdRegex = Regex("""vimeo\.com/(?:video/)?(\d+)""", RegexOption.IGNORE_CASE)
private val imageExtensions = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif")

private const val MAX_VIDEO_OPTIONS = 8
private const val RECOMMENDED_HEIGHT = 1080
private const val DEFAULT_AUDIO_BITRATE_KBPS = 128

/** Shown when yt-dlp cannot know a value (Instagram does not expose the duration, for example). */
private const val UNKNOWN_VALUE = "—"

private val audioSpecs = listOf(
    AudioSpec("MP3 · 320 kbps", "Máxima calidad", 320),
    AudioSpec("M4A · 256 kbps", "Alta calidad", 256),
    AudioSpec("Opus · 160 kbps", "Menor tamaño", 160),
    AudioSpec("MP3 · 128 kbps", "Compatibilidad universal", 128),
)

/**
 * Builds the analysis shown after the user submits a link.
 *
 * Passing [metadata] (resolved by yt-dlp on desktop/Android) replaces the title, duration and
 * thumbnail with the real ones, recomputes the estimated sizes from the real duration and builds
 * one resolution option per stream that actually exists for that video (labelled by their short
 * side, so portrait reels show 720p instead of 1280p).
 *
 * Without usable formats there is no honest resolution to show, so the only option is
 * [bestAvailableOption] and yt-dlp picks the real best stream on download. Nothing is fabricated:
 * when yt-dlp reports no duration (common on Instagram) duration and sizes render as
 * [UNKNOWN_VALUE]. The only direct network request made here is the thumbnail placeholder
 * (YouTube/Vimeo/direct image URLs).
 */
fun analyzeLink(
    rawUrl: String,
    metadata: VideoMetadata? = null,
): VideoAnalysis? {
    val url = rawUrl.trim()
    if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
        return null
    }

    val host = url.substringAfter("://")
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .removePrefix("www.")
        .lowercase()
    if (host.isBlank() || !host.contains('.')) return null

    val platform = platformName(host, url)
    val seconds = metadata?.durationSeconds?.takeIf { it > 0 }?.toInt()
    val duration = seconds?.let(::formatDuration) ?: UNKNOWN_VALUE
    val title = metadata?.title?.takeIf { it.isNotBlank() } ?: titleFor(platform, host)
    val thumbnailUrl = metadata?.thumbnailUrl?.takeIf { it.isNotBlank() }
        ?: thumbnailFor(url, platform)

    val availableFormats = metadata?.formats.orEmpty()
    val videoOptions = if (availableFormats.any { it.isVideo }) {
        dynamicVideoOptions(availableFormats, seconds)
    } else {
        listOf(bestAvailableOption())
    }

    return VideoAnalysis(
        url = url,
        platform = platform,
        host = host,
        title = title,
        duration = duration,
        thumbnailUrl = thumbnailUrl,
        videoOptions = videoOptions,
        audioOptions = audioSpecs.map { spec ->
            AudioQuality(
                label = spec.label,
                detail = spec.detail,
                size = seconds?.let { formatSize(spec.bitrateKbps, it) } ?: UNKNOWN_VALUE,
            )
        },
    )
}

/** Fallback when yt-dlp did not report any usable stream: let yt-dlp pick the best format. */
private fun bestAvailableOption(): VideoQuality = VideoQuality(
    label = "Mejor calidad",
    quality = null,
    format = "Automática",
    size = UNKNOWN_VALUE,
    recommended = true,
)

/**
 * Builds one option per resolution actually offered by yt-dlp, keeping the best stream of each
 * height. The download itself still targets `height <= N`, so the label always matches what the
 * user gets.
 */
private fun dynamicVideoOptions(
    formats: List<VideoFormatInfo>,
    seconds: Int?,
): List<VideoQuality> {
    val sourceAudioKbps = formats.filter { it.isAudioOnly }
        .mapNotNull { it.totalBitrateKbps }
        .maxOrNull()
        ?: DEFAULT_AUDIO_BITRATE_KBPS

    val options = formats.filter { it.isVideo }
        .groupBy { it.height!! }
        .map { (height, candidates) ->
            height to candidates.maxWith(
                compareBy<VideoFormatInfo>(
                    { it.totalBitrateKbps ?: 0 },
                    { it.fps ?: 0 },
                ),
            )
        }
        .map { (height, format) -> Triple(height, displayHeight(format), format) }
        .sortedByDescending { it.second }
        .take(MAX_VIDEO_OPTIONS)
        .map { (height, display, format) ->
            val videoKbps = format.totalBitrateKbps
                ?: bitrateFromBytes(format.fileSizeBytes, seconds)
                ?: estimatedVideoKbps(display)
            // Video-only streams get muxed with the best audio track, so its bitrate counts too.
            val totalKbps = if (format.audioCodec == null) videoKbps + sourceAudioKbps else videoKbps
            VideoQuality(
                label = "${display}p",
                quality = qualityTier(display),
                format = formatDescription(format),
                size = seconds?.let { formatSize(totalKbps, it) } ?: UNKNOWN_VALUE,
                maxHeight = height,
            )
        }

    if (options.isEmpty()) return listOf(bestAvailableOption())

    val recommendedIndex = options.indexOfFirst { heightOf(it) == RECOMMENDED_HEIGHT }
        .takeIf { it >= 0 }
        ?: options.indexOfFirst { heightOf(it) <= RECOMMENDED_HEIGHT }.takeIf { it >= 0 }
        ?: 0

    return options.mapIndexed { index, option ->
        option.copy(recommended = index == recommendedIndex)
    }
}

/**
 * Short side of the frame. Portrait reels are 720x1280, and users describe that as 720p even
 * though yt-dlp reports `height = 1280`; [VideoQuality.maxHeight] keeps the real height so the
 * download selector still matches.
 */
private fun displayHeight(format: VideoFormatInfo): Int {
    val height = format.height ?: 0
    val width = format.width
    return if (width != null && width in 1 until height) width else height
}

private fun heightOf(option: VideoQuality): Int =
    option.label.removeSuffix("p").trim().toIntOrNull() ?: 0

private fun qualityTier(height: Int): String? = when {
    height >= 2160 -> "4K"
    height >= 1440 -> "2K"
    height >= 1080 -> "Full HD"
    height >= 720 -> "HD"
    height >= 480 -> "SD"
    else -> null
}

private fun formatDescription(format: VideoFormatInfo): String {
    val container = format.extension?.uppercase()
    val codec = codecName(format.videoCodec)
    return when {
        container != null && codec != null -> "$container · $codec"
        container != null -> container
        codec != null -> codec
        else -> "Video"
    }
}

private fun codecName(codec: String?): String? = when {
    codec == null -> null
    codec.startsWith("avc", ignoreCase = true) ||
        codec.startsWith("h264", ignoreCase = true) -> "H.264"

    codec.startsWith("vp09", ignoreCase = true) ||
        codec.startsWith("vp9", ignoreCase = true) -> "VP9"

    codec.startsWith("av01", ignoreCase = true) ||
        codec.startsWith("av1", ignoreCase = true) -> "AV1"

    codec.startsWith("hev1", ignoreCase = true) ||
        codec.startsWith("hvc1", ignoreCase = true) ||
        codec.startsWith("hevc", ignoreCase = true) -> "HEVC"

    codec.startsWith("vp8", ignoreCase = true) -> "VP8"
    else -> codec.substringBefore('.').uppercase()
}

/** Rough bitrate for streams that report neither `tbr` nor `filesize`, only for the MB estimate. */
private fun estimatedVideoKbps(height: Int): Int = when {
    height >= 2160 -> 45_000
    height >= 1440 -> 25_000
    height >= 1080 -> 12_000
    height >= 720 -> 6_000
    height >= 480 -> 3_000
    else -> 1_500
}

private fun bitrateFromBytes(bytes: Long?, seconds: Int?): Int? {
    if (bytes == null || bytes <= 0 || seconds == null || seconds <= 0) return null
    return (bytes * 8 / seconds / 1000).toInt().takeIf { it > 0 }
}

private fun platformName(host: String, url: String): String = when {
    host.endsWith("youtube.com") || host == "youtu.be" -> "YouTube"
    host.endsWith("vimeo.com") -> "Vimeo"
    host.endsWith("tiktok.com") -> "TikTok"
    host.endsWith("instagram.com") -> "Instagram"
    host.endsWith("facebook.com") || host == "fb.watch" -> "Facebook"
    host.endsWith("x.com") || host.endsWith("twitter.com") -> "X"
    host.endsWith("dailymotion.com") || host == "dai.ly" -> "Dailymotion"
    isImageUrl(url) -> "Imagen"
    else -> host.substringBefore('.').replaceFirstChar { it.uppercase() }
}

private fun titleFor(platform: String, host: String): String = when (platform) {
    "YouTube" -> "Cargando titulo..."
    "Vimeo" -> "Cargando titulo..."
    "Imagen" -> "Imagen de $host"
    else -> "Contenido de $platform"
}

private fun thumbnailFor(url: String, platform: String): String? {
    val youtubeId = youtubeIdRegex.find(url)?.groupValues?.get(1)
    if (platform == "YouTube" && youtubeId != null) {
        return "https://img.youtube.com/vi/$youtubeId/hqdefault.jpg"
    }

    val vimeoId = vimeoIdRegex.find(url)?.groupValues?.get(1)
    if (platform == "Vimeo" && vimeoId != null) {
        return "https://vumbnail.com/$vimeoId.jpg"
    }

    return if (platform == "Imagen") url else null
}

private fun isImageUrl(url: String): Boolean {
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return imageExtensions.any { path.endsWith(it) }
}

private fun formatDuration(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}

private fun formatSize(bitrateKbps: Int, seconds: Int): String {
    val megabytes = (bitrateKbps.toLong() * seconds / 8 / 1024).coerceAtLeast(1)
    return if (megabytes >= 1024) {
        val gigabytes = (megabytes / 102.4).roundToInt() / 10.0
        "${gigabytes.toString().replace('.', ',')} GB"
    } else {
        "$megabytes MB"
    }
}
