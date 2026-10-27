package com.app.kdownloader.platform

internal const val YT_DLP_UNKNOWN_FAILURE = "yt-dlp terminó con un error desconocido."

/**
 * Turns the raw yt-dlp error output into something the user can act on.
 *
 * [fallback] is used when no `ERROR:` line was captured (for example when only the exit code is
 * known). The original line is always appended so failures stay debuggable.
 */
internal fun describeYtDlpFailure(errors: List<String>, fallback: String?): String {
    val raw = errors.lastOrNull()
        ?: fallback?.trim()?.takeIf { it.isNotEmpty() }
        ?: return YT_DLP_UNKNOWN_FAILURE

    val hint = when {
        raw.contains("HTTP Error 403", ignoreCase = true) ||
            raw.contains("Forbidden", ignoreCase = true) ->
            "El servidor rechazó la descarga (HTTP 403). Casi siempre indica que yt-dlp está " +
                "desactualizado o que el sitio exige verificación; actualiza yt-dlp y reintenta."

        raw.contains("Sign in to confirm", ignoreCase = true) ||
            raw.contains("not a bot", ignoreCase = true) ->
            "El sitio pidió confirmar que no eres un bot. Reintenta más tarde o usa otro enlace."

        raw.contains("Unable to extract initial state", ignoreCase = true) ||
            raw.contains("please report this issue", ignoreCase = true) ->
            "El sitio cambió su web y esta versión de yt-dlp todavía no puede extraer el vídeo. " +
                "Actualiza yt-dlp; si ya es la última versión, el sitio aún no está soportado."

        raw.contains("deleted or geo-restricted", ignoreCase = true) ->
            "El vídeo no existe, es privado o está bloqueado en tu región."

        raw.contains("Private video", ignoreCase = true) ||
            raw.contains("members-only", ignoreCase = true) ||
            raw.contains("Join this channel", ignoreCase = true) ->
            "El vídeo es privado o solo para miembros del canal, así que requiere una sesión con " +
                "cuenta."

        raw.contains("ffmpeg", ignoreCase = true) || raw.contains("ffprobe", ignoreCase = true) ->
            "Falta ffmpeg, necesario para unir imagen y sonido o convertir el audio. " +
                "Instálalo y vuelve a intentarlo."

        raw.contains("Requested format is not available", ignoreCase = true) ->
            "Este vídeo no ofrece el formato o la calidad solicitada."

        raw.contains("Unsupported URL", ignoreCase = true) ->
            "yt-dlp no reconoce ese enlace como un medio descargable."

        else -> return raw
    }

    return "$hint\n$raw"
}
