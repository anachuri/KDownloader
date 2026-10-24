package com.app.kdownloader.model

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
 * Builds the demo analysis shown after the user submits a link.
 *
 * There is no backend yet, so metadata and sizes are deterministic mock values derived from the
 * URL. The only real network request is the video thumbnail (YouTube/Vimeo/direct image URLs).
 */
fun analyzeLink(rawUrl: String): VideoAnalysis? {
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
    val seconds = 180 + (url.hashCode().absoluteValue % 660)
    val duration = formatDuration(seconds)

    return VideoAnalysis(
        url = url,
        platform = platform,
        host = host,
        title = titleFor(platform, host),
        duration = duration,
        thumbnailUrl = thumbnailFor(url, platform),
        videoOptions = videoSpecs.map { spec ->
            VideoQuality(
                label = spec.label,
                quality = spec.quality,
                format = spec.format,
                size = formatSize(spec.bitrateKbps, seconds),
                recommended = spec.recommended,
            )
        },
        audioOptions = audioSpecs.map { spec ->
            AudioQuality(
                label = spec.label,
                detail = spec.detail,
                size = formatSize(spec.bitrateKbps, seconds),
            )
        },
    )
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
    "YouTube" -> "Video de YouTube"
    "Vimeo" -> "Video de Vimeo"
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
