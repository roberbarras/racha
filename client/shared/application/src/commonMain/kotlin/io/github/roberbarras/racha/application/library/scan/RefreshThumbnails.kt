package io.github.roberbarras.racha.application.library.scan

import io.github.roberbarras.racha.application.library.CategoryRepository
import io.github.roberbarras.racha.application.library.LibraryRepository
import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.domain.library.ElementKind
import io.github.roberbarras.racha.domain.preferences.Settings

/**
 * Background job for posters (spec §3.3): folder artwork is picked by the scan; items without it get a frame at
 * ~10 % of their first video. Items left without a poster are drawn with the generated placeholder by the UI.
 */
class RefreshThumbnails(
    private val categories: CategoryRepository,
    private val library: LibraryRepository,
    private val renderer: ThumbnailRenderer,
    private val preferences: Preferences,
) {
    suspend operator fun invoke(): Int {
        if (!preferences.get(Settings.Thumbnails)) return 0
        var rendered = 0
        for (category in categories.all()) {
            for (root in categories.roots(category.key)) {
                for (item in library.itemsInRoot(category.key, root.key)) {
                    if (item.archived || item.posterPath != null) continue
                    val video = library.elements(item.id).firstOrNull { it.kind == ElementKind.VIDEO && !it.archived } ?: continue
                    val cacheKey = "${video.id.value}-${video.fileModifiedAt.toEpochMilliseconds()}"
                    val uri = renderer.render(root.location, video.relativePath, video.media.durationSec, FRACTION, cacheKey) ?: continue
                    library.upsertItem(item.copy(posterPath = uri))
                    rendered++
                }
            }
        }
        return rendered
    }

    private companion object {
        const val FRACTION = 0.10
    }
}
