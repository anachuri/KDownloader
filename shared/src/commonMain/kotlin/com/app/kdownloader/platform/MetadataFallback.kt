package com.app.kdownloader.platform

/**
 * Runs the metadata extraction, retrying with [YOUTUBE_FALLBACK_CLIENT_ARGS] when the first pass
 * reports no video stream.
 *
 * yt-dlp picks its YouTube clients from the available JavaScript runtimes, and a client that cannot
 * negotiate a PO token aborts the whole extraction instead of falling back (that is why Android
 * used to show only "Mejor calidad"). The default list already excludes the TV clients, but this
 * second attempt with an explicit, verified list keeps the real resolutions available if the
 * defaults change again.
 *
 * [run] receives the full argument list and returns yt-dlp's stdout, or `null` when the process
 * could not run. Keeping the retry here makes desktop and Android behave identically and covers it
 * with tests that need no network access.
 */
internal suspend fun fetchMetadataWithFallback(
    run: suspend (args: List<String>) -> String?,
): VideoMetadata? {
    val primary = run(metadataOptions())?.let(::parseMetadataOutput)
    if (primary != null && primary.hasVideoStreams()) return primary

    val fallback = run(metadataOptions(YOUTUBE_FALLBACK_CLIENT_ARGS))?.let(::parseMetadataOutput)
    if (fallback == null) return primary
    if (!fallback.hasVideoStreams()) return primary ?: fallback

    // Keep whatever the first pass already resolved; the retry is only needed for the streams.
    return primary?.let {
        fallback.copy(
            title = fallback.title ?: it.title,
            durationSeconds = fallback.durationSeconds ?: it.durationSeconds,
            thumbnailUrl = fallback.thumbnailUrl ?: it.thumbnailUrl,
        )
    } ?: fallback
}

private fun VideoMetadata.hasVideoStreams(): Boolean = formats.any { it.isVideo }
