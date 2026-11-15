package io.github.roberbarras.racha.android.resource

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.domain.resource.ExternalLinks
import io.github.roberbarras.racha.ui.resource.PdfDocument
import io.github.roberbarras.racha.ui.resource.ResourceHooks
import java.io.File

/** Android side of the resource viewer: locked WebView, PdfRenderer, system apps for links and files. */
class AndroidResourceHooks(private val context: Context) : ResourceHooks {
    @Composable
    override fun HtmlView(folder: String, page: RelativePath, onExternalLink: (String) -> Unit, modifier: Modifier) =
        CourseHtmlView(folder, page, onExternalLink, modifier)

    override fun openExternal(url: String) {
        if (!ExternalLinks.isSafe(url)) return
        start(Intent(Intent.ACTION_VIEW, url.toUri()))
    }

    /** Shared read-only through our FileProvider, so the PDF reader/browser can open it without storage access. */
    override fun openFile(path: String): Boolean {
        val file = File(path).takeIf { it.isFile } ?: return false
        val uri = runCatching { FileProvider.getUriForFile(context, "${context.packageName}.files", file) }.getOrNull() ?: return false
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        return start(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    override suspend fun openPdf(path: String): PdfDocument? = AndroidPdfDocument.open(path)

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
