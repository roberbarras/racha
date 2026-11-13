package io.github.roberbarras.racha.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.util.concurrent.TimeUnit

@Composable
actual fun rememberSystemReduceMotion(): Boolean = remember { DesktopMotion.reduced }

/** Read once at startup: GNOME `enable-animations` or KDE `AnimationDurationFactor`, if the tools exist. */
private object DesktopMotion {
    val reduced: Boolean by lazy {
        System.getenv("RACHA_REDUCE_MOTION") == "1" ||
            run("gsettings", "get", "org.gnome.desktop.interface", "enable-animations") == "false" ||
            run("kreadconfig6", "--file", "kdeglobals", "--group", "KDE", "--key", "AnimationDurationFactor")?.toDoubleOrNull() == 0.0
    }

    private fun run(vararg command: String): String? = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            null
        } else {
            process.inputStream.bufferedReader().readText().trim()
        }
    }.getOrNull()
}
