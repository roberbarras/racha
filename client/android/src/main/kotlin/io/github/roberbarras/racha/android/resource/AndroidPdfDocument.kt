package io.github.roberbarras.racha.android.resource

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import io.github.roberbarras.racha.ui.resource.PdfDocument
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** [PdfDocument] on the platform PdfRenderer, which allows one open page at a time: all calls on one thread. */
class AndroidPdfDocument private constructor(
    private val renderer: PdfRenderer,
    private val descriptor: ParcelFileDescriptor,
    private val thread: ExecutorCoroutineDispatcher,
    private val aspects: List<Float>,
) : PdfDocument {
    override val pageCount: Int get() = aspects.size

    override fun pageAspect(index: Int): Float = aspects[index]

    override suspend fun render(index: Int, widthPx: Int): ImageBitmap = withContext(thread) {
        renderer.openPage(index).use { page ->
            val height = (widthPx / aspects[index]).toInt().coerceAtLeast(1)
            val bitmap = createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE) // pages are transparent where nothing is drawn
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap.asImageBitmap()
        }
    }

    override fun close() {
        thread.executor.execute {
            renderer.close()
            descriptor.close()
        }
        thread.close()
    }

    companion object {
        suspend fun open(path: String): AndroidPdfDocument? {
            val thread = Executors.newSingleThreadExecutor { Thread(it, "pdf").apply { isDaemon = true } }.asCoroutineDispatcher()
            val document = withContext(thread) {
                runCatching {
                    val descriptor = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
                    val renderer = PdfRenderer(descriptor)
                    val aspects = (0 until renderer.pageCount).map { i -> renderer.openPage(i).use { it.width.toFloat() / it.height } }
                    AndroidPdfDocument(renderer, descriptor, thread, aspects)
                }.getOrNull()
            }
            if (document == null) thread.close()
            return document
        }
    }
}
