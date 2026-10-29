package com.app.kdownloader

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import javax.imageio.ImageIO

private object AppIconLoader

private val appIcon: Painter by lazy {
    val stream = AppIconLoader::class.java.getResourceAsStream("/icon.png")
        ?: error("No se encontró el recurso icon.png")
    BitmapPainter(stream.use { ImageIO.read(it).toComposeImageBitmap() })
}

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "KDownloader",
        icon = appIcon,
        state = rememberWindowState(width = 1180.dp, height = 840.dp),
    ) {
        App()
    }
}
