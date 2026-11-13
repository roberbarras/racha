package io.github.roberbarras.racha.ui.design

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.net.URI

internal actual fun decodeLocalImage(uri: String, maxWidth: Int): ImageBitmap? = runCatching {
    val file = if (uri.startsWith("file:")) File(URI(uri)) else File(uri)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()
