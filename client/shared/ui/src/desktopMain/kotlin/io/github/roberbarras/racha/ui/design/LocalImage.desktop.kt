package io.github.roberbarras.racha.ui.design

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

internal actual fun decodeLocalImage(uri: String, maxWidth: Int): ImageBitmap? = runCatching {
    val path = if (uri.startsWith("file:")) Path.of(URI(uri)) else Path.of(uri)
    val image = Image.makeFromEncoded(Files.readAllBytes(path))
    if (image.width <= maxWidth) return@runCatching image.toComposeImageBitmap()
    val height = image.height * maxWidth / image.width
    val surface = Surface.makeRasterN32Premul(maxWidth, height)
    surface.canvas.drawImageRect(image, Rect.makeWH(maxWidth.toFloat(), height.toFloat()))
    surface.makeImageSnapshot().toComposeImageBitmap()
}.getOrNull()
