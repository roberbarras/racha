package io.github.roberbarras.racha.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The theme with the user's colours (Settings → General): an accent, a tone for backgrounds and surfaces and a colour
 * for the side bar and the player's panels. The rest of the palette is derived from them so that text always reads:
 * backgrounds keep the tone and saturation of the chosen colour but Racha's lightness for each level (light and dark
 * mode alike), the bars stay dark under their light text, and text on the accent is dark or light by contrast.
 */
object ThemeColors {
    fun palette(base: RachaPalette, dark: Boolean, accent: Color?, background: Color?, rail: Color?): RachaPalette {
        var p = base
        if (background != null) p = withBackground(p, dark, background)
        if (rail != null) p = withRail(p, dark, rail)
        if (accent != null) p = withAccent(p, dark, accent)
        return p
    }

    /** Contrast the text keeps on backgrounds and bars (WCAG AAA for body text). */
    private const val TEXT_CONTRAST = 7f

    /**
     * [color] made lighter or darker (keeping its tone) until [text] on it reaches [ratio]: some hues, like green or
     * yellow, look brighter than their lightness says.
     */
    private fun readable(color: Color, text: Color, ratio: Float, lighter: Boolean): Color {
        val (h, s, start) = hsl(color)
        var l = start
        var c = color
        while (contrast(text, c) < ratio && l in 0f..1f) {
            l += if (lighter) 0.01f else -0.01f
            c = hsl(h, s, l)
        }
        return c
    }

    private fun withBackground(p: RachaPalette, dark: Boolean, color: Color): RachaPalette {
        val (h, s, _) = hsl(color)
        return if (dark) {
            val sat = min(s, 0.30f)
            p.copy(
                background = readable(hsl(h, sat, 0.08f), p.ink, TEXT_CONTRAST, lighter = false),
                surface = readable(hsl(h, sat, 0.11f), p.ink, TEXT_CONTRAST, lighter = false),
                surfaceRaised = readable(hsl(h, sat, 0.15f), p.ink, TEXT_CONTRAST, lighter = false),
                surfaceSunken = hsl(h, sat, 0.06f), border = hsl(h, sat, 0.20f), borderStrong = hsl(h, sat, 0.28f),
                progressTrack = hsl(h, sat, 0.21f),
            )
        } else {
            val sat = min(s, 0.45f)
            p.copy(
                background = readable(hsl(h, sat, 0.94f), p.ink, TEXT_CONTRAST, lighter = true),
                surface = readable(hsl(h, sat, 0.98f), p.ink, TEXT_CONTRAST, lighter = true),
                surfaceRaised = hsl(h, sat * 0.4f, 0.995f),
                surfaceSunken = hsl(h, sat, 0.89f), border = hsl(h, sat * 0.8f, 0.84f), borderStrong = hsl(h, sat * 0.7f, 0.73f),
                progressTrack = hsl(h, sat * 0.8f, 0.87f),
            )
        }
    }

    private fun withRail(p: RachaPalette, dark: Boolean, color: Color): RachaPalette {
        val (h, s, l) = hsl(color)
        // Dark enough under its light text whatever was chosen; darker still in dark mode.
        val rail = if (dark) hsl(h, min(s, 0.5f), (l * 0.5f).coerceIn(0.04f, 0.10f)) else hsl(h, min(s, 0.6f), l.coerceIn(0.10f, 0.22f))
        return p.copy(rail = readable(rail, p.onRail, TEXT_CONTRAST, lighter = false))
    }

    private fun withAccent(p: RachaPalette, dark: Boolean, accent: Color): RachaPalette {
        val (h, _, _) = hsl(accent)
        // Whichever reads better on it: a very dark shade of its own tone, or white.
        val darkText = hsl(h, 0.5f, 0.07f)
        val onAccent = if (contrast(darkText, accent) >= contrast(Color.White, accent)) darkText else Color.White
        return p.copy(accent = accent, onAccent = onAccent, accentSoft = accent.copy(alpha = if (dark) 0.22f else 0.18f))
    }

    /** Hue (0–360), saturation and lightness (0–1). */
    fun hsl(c: Color): Triple<Float, Float, Float> {
        val r = c.red
        val g = c.green
        val b = c.blue
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val l = (maxC + minC) / 2
        val d = maxC - minC
        if (d == 0f) return Triple(0f, 0f, l)
        val s = d / (1 - abs(2 * l - 1))
        val h = when (maxC) {
            r -> 60 * (((g - b) / d) % 6)
            g -> 60 * ((b - r) / d + 2)
            else -> 60 * ((r - g) / d + 4)
        }
        return Triple((h + 360) % 360, s.coerceIn(0f, 1f), l)
    }

    fun hsl(h: Float, s: Float, l: Float): Color = Color.hsl(h.coerceIn(0f, 360f), s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))

    /** WCAG contrast ratio between two colours (1–21). */
    fun contrast(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f
        val lb = b.luminance() + 0.05f
        return max(la, lb) / min(la, lb)
    }
}
