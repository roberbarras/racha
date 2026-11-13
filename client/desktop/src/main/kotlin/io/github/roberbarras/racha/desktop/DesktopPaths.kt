package io.github.roberbarras.racha.desktop

import java.nio.file.Path

/** XDG base directories (https://specifications.freedesktop.org/basedir-spec/). */
object DesktopPaths {
    private val home: Path = Path.of(System.getProperty("user.home"))

    val data: Path get() = xdg("XDG_DATA_HOME", home.resolve(".local/share")).resolve("racha")
    val cache: Path get() = xdg("XDG_CACHE_HOME", home.resolve(".cache")).resolve("racha")
    val thumbnails: Path get() = cache.resolve("thumbs")

    private fun xdg(variable: String, fallback: Path): Path =
        System.getenv(variable)?.takeIf { it.isNotBlank() }?.let(Path::of) ?: fallback
}
