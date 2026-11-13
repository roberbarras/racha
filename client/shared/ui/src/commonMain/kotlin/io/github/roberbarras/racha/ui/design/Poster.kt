package io.github.roberbarras.racha.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * 16:9 artwork: the local poster/thumbnail when available, otherwise a placeholder generated on the device from
 * the title (spec §3.3 "fallback: placeholder generado localmente"): tinted paper, diagonal rules and initials.
 */
@Composable
fun Poster(uri: String?, title: String, modifier: Modifier = Modifier, tint: Color = posterTint(title), overlay: @Composable BoxScope.() -> Unit = {}) {
    val image = rememberLocalImage(uri, maxWidth = 640)
    Box(modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(Racha.radius.m))) {
        if (image != null) {
            Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            PosterPlaceholder(title, tint)
        }
        overlay()
    }
}

@Composable
private fun PosterPlaceholder(title: String, tint: Color) {
    val colors = Racha.colors
    val base = lerp(colors.surfaceSunken, tint, if (Racha.isDark) 0.28f else 0.18f)
    val rule = lerp(base, tint, 0.35f)
    Box(Modifier.fillMaxSize().background(base), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val step = size.width / 9f
            var x = -size.height
            while (x < size.width) {
                drawLine(rule, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 1.5f)
                x += step
            }
        }
        RText(
            initials(title),
            modifier = Modifier.padding(Racha.space.l),
            style = Racha.type.display,
            color = lerp(colors.ink, tint, 0.45f),
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/** Muted, distinct hue per title for placeholders (no gradients: one tone, rules and initials). */
fun posterTint(title: String): Color = POSTER_TINTS[(title.hashCode() and Int.MAX_VALUE) % POSTER_TINTS.size]

private val POSTER_TINTS = listOf(
    Color(0xFFE8812A), // ember
    Color(0xFF2E7D57), // pine
    Color(0xFF2F6FB0), // harbour blue
    Color(0xFFB9382A), // brick
    Color(0xFFB7791F), // ochre
    Color(0xFF1B8A7E), // teal
    Color(0xFF5E6B7E), // slate
)

internal fun initials(title: String): String =
    title.split(' ', '-', '_', '.').filter { w -> w.firstOrNull()?.isLetterOrDigit() == true }
        .take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "·" }

/** Decodes a local `file://` image off the main thread, downsampled to [maxWidth], memory-cached. */
@Composable
fun rememberLocalImage(uri: String?, maxWidth: Int): ImageBitmap? {
    val image by produceState<ImageBitmap?>(LocalImageCache.get(uri, maxWidth), uri, maxWidth) {
        if (uri != null && value == null) {
            value = withContext(Dispatchers.IO) { decodeLocalImage(uri, maxWidth) }?.also { LocalImageCache.put(uri, maxWidth, it) }
        }
    }
    return image
}

private object LocalImageCache {
    private const val CAPACITY = 96
    private val entries = object : LinkedHashMap<String, ImageBitmap>(CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > CAPACITY
    }

    fun get(uri: String?, width: Int): ImageBitmap? = uri?.let { synchronized(entries) { entries["$width|$it"] } }

    fun put(uri: String, width: Int, image: ImageBitmap) = synchronized(entries) { entries["$width|$uri"] = image }
}

/** Platform image decoding with downsampling; null if the file is missing or not an image. */
internal expect fun decodeLocalImage(uri: String, maxWidth: Int): ImageBitmap?
