package com.app.kdownloader.platform

import kotlinx.browser.window
import kotlinx.coroutines.suspendCancellableCoroutine
import org.khronos.webgl.Int8Array
import org.khronos.webgl.toByteArray
import org.w3c.fetch.RequestInit
import kotlin.coroutines.resume
import kotlin.js.JsAny

internal expect fun emptyRequestInit(): RequestInit

actual suspend fun fetchImageBytes(url: String): ByteArray? =
    suspendCancellableCoroutine { continuation ->
        window.fetch(url, emptyRequestInit()).then<JsAny?> { response ->
            if (response.ok) {
                response.arrayBuffer().then<JsAny?> { buffer ->
                    continuation.resume(Int8Array(buffer).toByteArray())
                    null
                }
            } else {
                continuation.resume(null)
            }
            null
        }
    }
