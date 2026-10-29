package com.app.kdownloader.model

import com.app.kdownloader.platform.VideoFormatInfo
import com.app.kdownloader.platform.VideoMetadata
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

data class VideoQuality(
    val label: String,
    val quality: String?,
    val format: String,
    val size: String,
    val recommended: Boolean = false,
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

private data class VideoSpec(
    val label: String,
    val quality: String?,
    val bitrateKbps: Int,
    val format: String,
    val recommended: Boolean = false,
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

private val videoSpecs = listOf(
    VideoSpec("2160p", "4K", 45_000, "WebM · VP9"),
    VideoSpec("1440p", "2K", 25_000, "WebM · VP9"),
    VideoSpec("1080p", "Full HD", 12_000, "MP4 · H.264", recommended = true),
    VideoSpec("720p", "HD", 6_000, "MP4 · H.264"),
    VideoSpec("480p", "SD", 3_000, "MP4 · H.264"),
    VideoSpec("360p", null, 1_500, "MP4 · H.264"),
)

private val audioSpecs = listOf(
    AudioSpec("MP3 · 320 kbps", "Máxima calidad", 320),
    AudioSpec("M4A · 256 kbps", "Alta calidad", 256),
    AudioSpec("Opus · 160 kbps", "Menor tamaño", 160),
    AudioSpec("MP3 · 128 kbps", "Compatibilidad universal", 128),
)

/**
 * Builds the analysis shown after the user submits a link.
 *
 * There is no backend yet, so titles, durations and sizes come from deterministic mock values
 * derived from the URL. Passing [metadata] (resolved by yt-dlp on desktop/Android) replaces the
 * title, duration and thumbnail with the real ones, recomputes the estimated sizes from the real
 * duration and, when yt-dlp reported the available streams, replaces the static resolution list
 * with the resolutions that actually exist for that video. The only direct network request made
 * here is the thumbnail placeholder (YouTube/Vimeo/direct image URLs).
 */
fun analyzeLink(rawUrl: String, metadata: VideoMetadata? = null): VideoAnalysis? {
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
    val seconds: Int = metadata?.durationSeconds?.takeIf { it > 0 }?.toInt()
        ?: (180 + (url.hashCode().absoluteValue % 660))
    val duration = formatDuration(seconds)
    val title = metadata?.title?.takeIf { it.isNotBlank() } ?: titleFor(platform, host)
    val thumbnailUrl = metadata?.thumbnailUrl?.takeIf { it.isNotBlank() }
        ?: thumbnailFor(url, platform)

    val availableFormats = metadata?.formats.orEmpty()
    val videoOptions = if (availableFormats.any { it.isVideo }) {
        dynamicVideoOptions(availableFormats, seconds)
    } else {
        staticVideoOptions(seconds)
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
                size = formatSize(spec.bitrateKbps, seconds),
            )
        },
    )
}

private fun staticVideoOptions(seconds: Int): List<VideoQuality> = videoSpecs.map { spec ->
    VideoQuality(
        label = spec.label,
        quality = spec.quality,
        format = spec.format,
        size = formatSize(spec.bitrateKbps, seconds),
        recommended = spec.recommended,
    )
}

/**
 * Builds one option per resolution actually offered by yt-dlp, keeping the best stream of each
 * height. The download itself still targets `height <= N`, so the label always matches what the
 * user gets.
 */
private fun dynamicVideoOptions(
    formats: List<VideoFormatInfo>,
    seconds: Int,
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
        .sortedByDescending { it.first }
        .take(MAX_VIDEO_OPTIONS)
        .map { (height, format) ->
            val videoKbps = format.totalBitrateKbps
                ?: bitrateFromBytes(format.fileSizeBytes, seconds)
                ?: estimatedVideoKbps(height)
            // Video-only streams get muxed with the best audio track, so its bitrate counts too.
            val totalKbps = if (format.audioCodec == null) videoKbps + sourceAudioKbps else videoKbps
            VideoQuality(
                label = "${height}p",
                quality = qualityTier(height),
                format = formatDescription(format),
                size = formatSize(totalKbps, seconds),
            )
        }

    if (options.isEmpty()) return staticVideoOptions(seconds)

    val recommendedIndex = options.indexOfFirst { heightOf(it) == RECOMMENDED_HEIGHT }
        .takeIf { it >= 0 }
        ?: options.indexOfFirst { heightOf(it) <= RECOMMENDED_HEIGHT }.takeIf { it >= 0 }
        ?: 0

    return options.mapIndexed { index, option ->
        option.copy(recommended = index == recommendedIndex)
    }
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

/** Uses the closest lower static tier as a fallback when yt-dlp reports no bitrate at all. */
private fun estimatedVideoKbps(height: Int): Int =
    videoSpecs.firstOrNull { (labelHeight(it.label) ?: 0) <= height }?.bitrateKbps
        ?: videoSpecs.last().bitrateKbps

private fun labelHeight(label: String): Int? = label.removeSuffix("p").trim().toIntOrNull()

private fun bitrateFromBytes(bytes: Long?, seconds: Int): Int? {
    if (bytes == null || bytes <= 0 || seconds <= 0) return null
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
