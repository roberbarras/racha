package io.github.roberbarras.racha.desktop

import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.util.concurrent.TimeUnit

/**
 * How much bigger than 1 dp = 1 pixel the UI must be drawn. Java on X11/XWayland only honours the integer
 * GDK_SCALE, so on a scaled desktop (KDE/GNOME at 150 %, 170 %… with "X11 apps scale themselves") it would draw
 * everything at 100 %, tiny on a 4K screen. The desktop announces its scale to X11 apps as `Xft.dpi` (96 = 100 %):
 * we read it and apply the remainder in Compose's density, which handles fractional factors crisply.
 *
 * Order: `RACHA_SCALE` (manual override, e.g. 1.25) → `Xft.dpi` from the X resources → `Xft/DPI` from XSETTINGS →
 * `GDK_SCALE` → 1. Read once at start-up: the X11 protocol has a single scale for every screen.
 */
object DesktopUiScale {
    /** Detected once per process (what "automatic" means in Settings). */
    val detected: Float by lazy { detect() }

    /** The scale in use: the one chosen in Settings (0 = automatic) once read at start-up and on every change. */
    @Volatile var current: Float = 1f
        private set

    /** [chosen] is the setting (0 = automatic). */
    fun apply(chosen: Double) {
        current = if (chosen <= 0.0) detected else (chosen.toFloat() / awtScale()).coerceIn(MIN_SCALE, MAX_SCALE)
    }

    private fun detect(): Float {
        val target = System.getenv("RACHA_SCALE")?.toFloatOrNull()
            ?: xResourcesDpi()?.let { it / BASE_DPI }
            ?: xSettingsDpi()?.let { it / BASE_DPI }
            ?: System.getenv("GDK_SCALE")?.toFloatOrNull()
            ?: 1f
        return (target / awtScale()).coerceIn(MIN_SCALE, MAX_SCALE)
    }

    /** What AWT already applies (GDK_SCALE or sun.java2d.uiScale); Compose's density includes it. */
    private fun awtScale(): Float = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX.toFloat()
    }.getOrDefault(1f).coerceAtLeast(1f)

    private fun xResourcesDpi(): Float? = runCatching {
        val process = ProcessBuilder("xrdb", "-query").redirectError(ProcessBuilder.Redirect.DISCARD).start()
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroy().let { return null }
        process.inputStream.bufferedReader().readLines()
            .firstOrNull { it.startsWith("Xft.dpi:") }?.substringAfter(':')?.trim()?.toFloatOrNull()
    }.getOrNull()?.takeIf { it > 0 }

    /** XSETTINGS reports DPI × 1024. */
    private fun xSettingsDpi(): Float? = runCatching {
        (Toolkit.getDefaultToolkit().getDesktopProperty("gnome.Xft/DPI") as? Int)?.let { it / 1024f }
    }.getOrNull()?.takeIf { it > 0 }

    private const val BASE_DPI = 96f
    private const val MIN_SCALE = 0.75f
    private const val MAX_SCALE = 4f
}
