package com.app.kdownloader

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform