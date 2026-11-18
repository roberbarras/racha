package io.github.roberbarras.racha.desktop

import java.nio.file.Path

/** XDG base directories (https://specifications.freedesktop.org/basedir-spec/). */
object DesktopPaths {
    private val home: Path = Path.of(System.getProperty("user.home"))

    val data: Path get() = xdg("XDG_DATA_HOME", home.resolve(".local/share")).resolve("racha")
    val cache: Path get() = xdg("XDG_CACHE_HOME", home.resolve(".cache")).resolve("racha")
    val thumbnails: Path get() = cache.resolve("thumbs")

    /** The user's videos folder as xdg-user-dirs names it (`~/Vídeos` in Spanish), or `~/Videos`: where remote courses go (H19). */
    val videos: Path
        get() = runCatching {
            val config = xdg("XDG_CONFIG_HOME", home.resolve(".config")).resolve("user-dirs.dirs")
            java.nio.file.Files.readAllLines(config).firstOrNull { it.startsWith("XDG_VIDEOS_DIR=") }
                ?.substringAfter('=')?.trim('"')?.replace("\$HOME", home.toString())?.let(Path::of)
        }.getOrNull()?.takeIf { it != home } ?: home.resolve("Videos")

    private fun xdg(variable: String, fallback: Path): Path =
        System.getenv(variable)?.takeIf { it.isNotBlank() }?.let(Path::of) ?: fallback
}
