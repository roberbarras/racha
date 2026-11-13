package io.github.roberbarras.racha.android.library

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import io.github.roberbarras.racha.application.library.scan.MediaProbe
import io.github.roberbarras.racha.domain.library.MediaInfo
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.files.NioLibrarySource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Metadata with the platform demuxers (MediaExtractor + MediaMetadataRetriever): no decoding, no extra libraries. */
class AndroidMediaProbe : MediaProbe {

    override suspend fun probe(location: String, path: RelativePath): MediaInfo? = withContext(Dispatchers.IO) {
        val file = NioLibrarySource.pathOf(location).resolve(path.value).toString()
        runCatching {
            val extractor = MediaExtractor()
            var video: MediaFormat? = null
            try {
                extractor.setDataSource(file)
                video = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                    .firstOrNull { it.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/") }
            } finally {
                extractor.release()
            }
            val retriever = MediaMetadataRetriever()
            val (durationMs, bitrate) = try {
                retriever.setDataSource(file)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() to
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
            } finally {
                retriever.release()
            }
            MediaInfo(
                durationSec = durationMs?.let { (it + 500) / 1000 },
                width = video?.integerOrNull(MediaFormat.KEY_WIDTH),
                height = video?.integerOrNull(MediaFormat.KEY_HEIGHT),
                videoCodec = video?.getString(MediaFormat.KEY_MIME)?.let(::codecName),
                fps = video?.frameRate(),
                bitrateKbps = bitrate?.let { (it / 1000).toInt() },
            )
        }.getOrNull()
    }

    private fun MediaFormat.integerOrNull(key: String) = if (containsKey(key)) getInteger(key) else null

    /** KEY_FRAME_RATE is an Integer or a Float depending on the extractor. */
    private fun MediaFormat.frameRate(): Double? {
        if (!containsKey(MediaFormat.KEY_FRAME_RATE)) return null
        return runCatching { getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }
            .recoverCatching { getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() }
            .getOrNull()
    }

    /** Same codec names libmpv reports on desktop, so metadata reads the same on both platforms. */
    private fun codecName(mime: String): String = when (mime) {
        MediaFormat.MIMETYPE_VIDEO_AVC -> "h264"
        MediaFormat.MIMETYPE_VIDEO_HEVC -> "hevc"
        MediaFormat.MIMETYPE_VIDEO_VP9 -> "vp9"
        MediaFormat.MIMETYPE_VIDEO_VP8 -> "vp8"
        MediaFormat.MIMETYPE_VIDEO_AV1 -> "av1"
        MediaFormat.MIMETYPE_VIDEO_MPEG4 -> "mpeg4"
        MediaFormat.MIMETYPE_VIDEO_MPEG2 -> "mpeg2video"
        else -> mime.removePrefix("video/")
    }
}
