package io.github.roberbarras.racha.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/**
 * `Racha.AppImage --install` / `--uninstall`: adds (or removes) the app to the user's menu with its icon and makes it
 * the handler of `racha://` links (ADR-0009), only under `~/.local/share` (XDG_DATA_HOME). Nothing system-wide.
 */
class DesktopIntegration(private val dataHome: Path, private val run: (List<String>) -> Boolean = ::runQuietly) {
    private val desktopFile = dataHome.resolve("applications/$ID.desktop")
    private val icon = dataHome.resolve("icons/hicolor/256x256/apps/$ID.png")

    /** [appImage]: what the menu entry runs (the AppImage's own path); [iconSource]: the PNG inside it. */
    fun install(appImage: Path, iconSource: Path?): Path {
        Files.createDirectories(desktopFile.parent)
        Files.writeString(desktopFile, entry(appImage))
        if (iconSource != null && Files.isRegularFile(iconSource)) {
            Files.createDirectories(icon.parent)
            Files.copy(iconSource, icon, StandardCopyOption.REPLACE_EXISTING)
        }
        run(listOf("update-desktop-database", desktopFile.parent.toString()))
        run(listOf("xdg-mime", "default", "$ID.desktop", "x-scheme-handler/racha"))
        return desktopFile
    }

    fun uninstall() {
        Files.deleteIfExists(desktopFile)
        Files.deleteIfExists(icon)
        run(listOf("update-desktop-database", desktopFile.parent.toString()))
    }

    internal fun entry(appImage: Path): String = """
        |[Desktop Entry]
        |Type=Application
        |Name=Racha
        |Comment=Tus cursos y series locales, sin conexión
        |Exec="${appImage.toAbsolutePath()}" %u
        |Icon=$ID
        |Categories=AudioVideo;Video;Player;
        |MimeType=x-scheme-handler/racha;
        |Terminal=false
        |StartupWMClass=io-github-roberbarras-racha-desktop-MainKt
        |""".trimMargin()

    companion object {
        const val ID = "io.github.roberbarras.racha"

        fun dataHome(): Path = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: Path.of(System.getProperty("user.home"), ".local/share")

        private fun runQuietly(command: List<String>): Boolean = runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
        }.getOrDefault(false)
    }
}
