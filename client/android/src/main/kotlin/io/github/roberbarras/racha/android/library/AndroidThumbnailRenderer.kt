package io.github.roberbarras.racha.android.library

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import io.github.roberbarras.racha.application.library.scan.ThumbnailRenderer
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.files.NioLibrarySource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Frame at [fraction] of the video via MediaMetadataRetriever, scaled to 480 px wide, cached as WebP. */
class AndroidThumbnailRenderer(private val cacheDir: File) : ThumbnailRenderer {

    override suspend fun render(location: String, path: RelativePath, durationSec: Long?, fraction: Double, cacheKey: String): String? =
        withContext(Dispatchers.IO) {
            val target = File(cacheDir, "$cacheKey.webp")
            if (target.exists()) return@withContext target.toURI().toString()
            cacheDir.mkdirs()
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(NioLibrarySource.pathOf(location).resolve(path.value).toString())
                val durationMs = durationSec?.times(1000)
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                val frame = retriever.getFrameAtTime((durationMs * fraction * 1000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: return@withContext null
                val scaled = if (frame.width > WIDTH) {
                    Bitmap.createScaledBitmap(frame, WIDTH, frame.height * WIDTH / frame.width, true).also { frame.recycle() }
                } else {
                    frame
                }
                val temp = File(cacheDir, "$cacheKey.tmp")
                temp.outputStream().use { scaled.compress(webp(), QUALITY, it) }
                scaled.recycle()
                if (!temp.renameTo(target)) return@withContext null
                target.toURI().toString()
            } catch (_: RuntimeException) {
                null
            } finally {
                retriever.release()
            }
        }

    @Suppress("DEPRECATION")
    private fun webp(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP

    private companion object {
        const val WIDTH = 480
        const val QUALITY = 80
    }
}
