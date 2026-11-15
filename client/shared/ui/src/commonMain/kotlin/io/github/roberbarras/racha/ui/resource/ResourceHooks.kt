package io.github.roberbarras.racha.ui.resource

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import io.github.roberbarras.racha.domain.library.RelativePath

/** What the shared resource viewer needs from the platform (spec §5, ADR-0003). */
interface ResourceHooks {
    /**
     * A local web page in an embedded browser, served only from [folder] (the course): [page] is relative to it.
     * Links leaving the course go to [onExternalLink] (the system browser), never inside the viewer.
     */
    @Composable
    fun HtmlView(folder: String, page: RelativePath, onExternalLink: (String) -> Unit, modifier: Modifier)

    /** Opens a web/mail link in the system browser. */
    fun openExternal(url: String)

    /** Opens a local file with the system's default app; false where that is not possible. */
    fun openFile(path: String): Boolean

    /** Opens a PDF for rendering, or null if it cannot be read. Called off the main thread. */
    suspend fun openPdf(path: String): PdfDocument?
}

/** A PDF being viewed: pages are rendered on demand at the width they are shown (sharp, and no more memory). */
interface PdfDocument : AutoCloseable {
    val pageCount: Int

    /** Width / height of page [index]. */
    fun pageAspect(index: Int): Float

    suspend fun render(index: Int, widthPx: Int): ImageBitmap
}
