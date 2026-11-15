package io.github.roberbarras.racha.desktop.resource

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.domain.resource.ExternalLinks
import io.github.roberbarras.racha.ui.resource.PdfDocument
import io.github.roberbarras.racha.ui.resource.ResourceHooks
import java.io.File

/** Linux side of the resource viewer: JavaFX WebKit for HTML, PDFBox for PDF, `xdg-open` for "open outside". */
class DesktopResourceHooks : ResourceHooks {
    @Composable
    override fun HtmlView(folder: String, page: RelativePath, onExternalLink: (String) -> Unit, modifier: Modifier) =
        JavaFxWebView(folder, page, onExternalLink, modifier)

    override fun openExternal(url: String) {
        if (ExternalLinks.isSafe(url)) xdgOpen(url)
    }

    override fun openFile(path: String): Boolean = File(path).isFile && xdgOpen(path)

    override suspend fun openPdf(path: String): PdfDocument? = PdfBoxDocument.open(path)

    /** The desktop's default app (browser, PDF reader…), detached from ours. */
    private fun xdgOpen(target: String): Boolean = runCatching {
        ProcessBuilder("xdg-open", target).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        true
    }.getOrDefault(false)
}
