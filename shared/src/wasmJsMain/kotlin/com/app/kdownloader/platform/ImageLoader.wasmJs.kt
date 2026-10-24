package com.app.kdownloader.platform

import kotlin.js.ExperimentalWasmJsInterop
import org.w3c.fetch.RequestInit

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("() => ({})")
private external fun emptyRequestInitObject(): RequestInit

internal actual fun emptyRequestInit(): RequestInit = emptyRequestInitObject()
