package com.app.kdownloader.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI

actual suspend fun fetchImageBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
    runCatching {
        val connection = URI(url).toURL().openConnection()
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.getInputStream().use { it.readBytes() }
    }.getOrNull()
}
