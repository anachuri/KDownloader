package com.app.kdownloader.platform

import org.w3c.fetch.RequestInit

// A plain object literal is required: RequestInit() fills omitted fields with null, which browsers
// reject for enum-typed fields such as `cache`.
@Suppress("UnsafeCastFromDynamic")
internal actual fun emptyRequestInit(): RequestInit = js("({})")
