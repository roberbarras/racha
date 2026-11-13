package io.github.roberbarras.racha.ui.design

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.roberbarras.racha.ui.resources.Res
import io.github.roberbarras.racha.ui.resources.instrument_sans
import org.jetbrains.compose.resources.Font

/** Text styles of the type scale (design/tokens.json → typography). [number] variants use tabular figures. */
@Immutable
data class RachaTypography(
    val display: TextStyle,
    val title: TextStyle,
    val heading: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
    val overline: TextStyle,
) {
    /** Same style with tabular figures, so timecodes like 12:34 / 45:10 do not jitter. */
    fun TextStyle.numeric(): TextStyle = copy(fontFeatureSettings = "tnum")
}

private val LocalPalette = staticCompositionLocalOf { Tokens.Light }
private val LocalTypography = staticCompositionLocalOf<RachaTypography> { error("RachaTheme missing") }
private val LocalReduceMotion = staticCompositionLocalOf { false }
private val LocalIsDark = staticCompositionLocalOf { false }

/** Entry point to the design system: `Racha.colors.accent`, `Racha.type.title`, `Racha.space.l`… */
object Racha {
    val colors: RachaPalette @Composable @ReadOnlyComposable get() = LocalPalette.current
    val type: RachaTypography @Composable @ReadOnlyComposable get() = LocalTypography.current
    val isDark: Boolean @Composable @ReadOnlyComposable get() = LocalIsDark.current
    val reduceMotion: Boolean @Composable @ReadOnlyComposable get() = LocalReduceMotion.current
    val space = Tokens.Space
    val radius = Tokens.Radius
    val layout = Tokens.Layout

    /** Motion duration in ms, 0 when the system asks to reduce motion (spec §10.1). */
    @Composable
    @ReadOnlyComposable
    fun duration(ms: Int): Int = if (LocalReduceMotion.current) 0 else ms

    val motion = Tokens.Motion
}

/**
 * @param accent user accent from settings (spec §9 "color de acento"); null keeps the brand ember.
 * @param fontScale user font size setting.
 */
@Composable
fun RachaTheme(
    dark: Boolean,
    accent: Color? = null,
    fontScale: Float = 1f,
    reduceMotion: Boolean = rememberSystemReduceMotion(),
    content: @Composable () -> Unit,
) {
    val base = if (dark) Tokens.Dark else Tokens.Light
    val palette = remember(base, accent) {
        if (accent == null) base else base.copy(accent = accent, accentSoft = accent.copy(alpha = if (dark) 0.22f else 0.18f))
    }
    val family = instrumentSans()
    val typography = remember(family, fontScale) { typography(family, fontScale) }
    val selection = remember(palette) { TextSelectionColors(palette.accent, palette.accent.copy(alpha = 0.3f)) }
    CompositionLocalProvider(
        LocalPalette provides palette,
        LocalTypography provides typography,
        LocalReduceMotion provides reduceMotion,
        LocalIsDark provides dark,
        LocalTextSelectionColors provides selection,
        content = content,
    )
}

/** Instrument Sans variable font, instantiated at every weight of the type scale. */
@Composable
private fun instrumentSans(): FontFamily {
    val weights = listOf(420, 450, 560, 600, 650, 680, 700)
    return FontFamily(
        weights.map { w ->
            Font(Res.font.instrument_sans, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
        },
    )
}

private fun typography(family: FontFamily, scale: Float): RachaTypography {
    fun style(t: TypeToken) = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight(t.weight),
        fontSize = (t.size * scale).sp,
        lineHeight = (t.lineHeight * scale).sp,
        letterSpacing = (t.tracking / t.size).em,
        color = Color.Unspecified,
    )
    return RachaTypography(
        display = style(Tokens.Type.display),
        title = style(Tokens.Type.title),
        heading = style(Tokens.Type.heading),
        body = style(Tokens.Type.body),
        bodyStrong = style(Tokens.Type.bodyStrong),
        label = style(Tokens.Type.label),
        caption = style(Tokens.Type.caption),
        overline = style(Tokens.Type.overline),
    )
}

/** Whether the OS asks for reduced motion (Android animator scale 0, GNOME/KDE animations off). */
@Composable
expect fun rememberSystemReduceMotion(): Boolean
