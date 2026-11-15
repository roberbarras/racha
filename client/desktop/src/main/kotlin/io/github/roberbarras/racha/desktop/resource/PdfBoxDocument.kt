package io.github.roberbarras.racha.desktop.resource

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.github.roberbarras.racha.ui.resource.PdfDocument
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.RandomAccessReadBufferedFile
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.util.concurrent.Executors

/** [PdfDocument] on PDFBox. PDFBox documents are not thread-safe: every call runs on one dedicated thread. */
class PdfBoxDocument private constructor(
    private val document: PDDocument,
    private val thread: ExecutorCoroutineDispatcher,
    private val aspects: List<Float>,
) : PdfDocument {
    private val renderer = PDFRenderer(document)

    override val pageCount: Int get() = aspects.size

    override fun pageAspect(index: Int): Float = aspects[index]

    override suspend fun render(index: Int, widthPx: Int): ImageBitmap = withContext(thread) {
        val pageWidthPt = document.getPage(index).cropBox.width
        renderer.renderImage(index, widthPx / pageWidthPt, ImageType.RGB).toComposeImageBitmap()
    }

    override fun close() {
        thread.executor.execute { document.close() }
        thread.close()
    }

    companion object {
        /**
         * PDFBox maps non-embedded fonts to system fonts and, the first time, indexes every installed font (about
         * 15 s on a desktop with many fonts; 0.6 s once cached). The index lives in our cache folder, not in $HOME.
         * Must run before any PDFBox class loads.
         */
        fun configure(cacheDir: java.nio.file.Path) {
            java.nio.file.Files.createDirectories(cacheDir)
            System.setProperty("pdfbox.fontcache", cacheDir.toString())
        }

        fun fontIndexExists(cacheDir: java.nio.file.Path): Boolean = java.nio.file.Files.exists(cacheDir.resolve(".pdfbox.cache"))

        /** Builds the font index ahead of time (one-off, background), so the first PDF opens without the wait. */
        fun buildFontIndex() {
            org.apache.pdfbox.pdmodel.font.FontMappers.instance().getFontBoxFont("Helvetica", null)
        }

        suspend fun open(path: String): PdfBoxDocument? {
            val thread = Executors.newSingleThreadExecutor { Thread(it, "pdfbox").apply { isDaemon = true } }.asCoroutineDispatcher()
            return withContext(thread) {
                runCatching {
                    val document = Loader.loadPDF(RandomAccessReadBufferedFile(path))
                    val aspects = (0 until document.numberOfPages).map { i ->
                        val box = document.getPage(i).cropBox
                        val rotated = document.getPage(i).rotation % 180 != 0
                        if (rotated) box.height / box.width else box.width / box.height
                    }
                    PdfBoxDocument(document, thread, aspects)
                }.getOrNull()
            } ?: null.also { thread.close() }
        }
    }
}
