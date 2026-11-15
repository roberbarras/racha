package io.github.roberbarras.racha.ui.resource

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import io.github.roberbarras.racha.ui.design.MessageState
import io.github.roberbarras.racha.ui.design.RIconButton
import io.github.roberbarras.racha.ui.design.RText
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.RachaIcons
import io.github.roberbarras.racha.ui.design.SkeletonBlock
import io.github.roberbarras.racha.ui.resources.Res
import io.github.roberbarras.racha.ui.resources.pdf_unreadable
import io.github.roberbarras.racha.ui.resources.zoom_in
import io.github.roberbarras.racha.ui.resources.zoom_out
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

private sealed interface PdfLoad {
    data object Loading : PdfLoad
    data object Failed : PdfLoad
    data class Ready(val document: PdfDocument) : PdfLoad
}

/**
 * Integrated PDF viewer (user decision; Android PdfRenderer, Linux PDFBox): pages in a vertical list, each rendered
 * only while on screen and at the size it is shown, so a 300-page book costs what two pages cost. Zoom by steps.
 */
@Composable
fun PdfViewer(hooks: ResourceHooks, path: String) {
    val load by produceState<PdfLoad>(PdfLoad.Loading, path) {
        val document = withContext(Dispatchers.Default) { runCatching { hooks.openPdf(path) }.getOrNull() }
        value = document?.let(PdfLoad::Ready) ?: PdfLoad.Failed
        awaitDispose { document?.close() }
    }
    when (val l = load) {
        PdfLoad.Loading -> SkeletonBlock(Modifier.fillMaxSize().padding(Racha.space.xl))
        PdfLoad.Failed -> MessageState(RachaIcons.FileText, "", stringResource(Res.string.pdf_unreadable))
        is PdfLoad.Ready -> Pages(l.document)
    }
}

@Composable
private fun Pages(document: PdfDocument) {
    var zoom by remember { mutableFloatStateOf(1f) }
    val listState = rememberLazyListState()
    BoxWithConstraints(Modifier.fillMaxSize().background(Racha.colors.surfaceSunken)) {
        val available = maxWidth
        // "Fit width", like PDF readers: slides and documents fill the viewer; zoom goes from there.
        val fit = min(available - Racha.space.xl * 2, MAX_PAGE_WIDTH)
        val pageWidth = fit * zoom
        val horizontal = rememberScrollState()
        Box(Modifier.fillMaxSize().horizontalScroll(horizontal), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.width(maxOf(pageWidth + Racha.space.xl * 2, available)).fillMaxHeight(),
                state = listState,
                contentPadding = PaddingValues(vertical = Racha.space.xl),
                verticalArrangement = Arrangement.spacedBy(Racha.space.l),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(document.pageCount) { index -> Page(document, index, pageWidth) }
            }
        }
        Row(
            Modifier.align(Alignment.BottomEnd).padding(Racha.space.l).clip(RoundedCornerShape(Racha.radius.pill))
                .background(Racha.colors.surfaceRaised).padding(horizontal = Racha.space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RIconButton(RachaIcons.ZoomOut, stringResource(Res.string.zoom_out), { zoom = (zoom / ZOOM_STEP).coerceAtLeast(MIN_ZOOM) })
            RText("${listState.firstVisibleItemIndex + 1} / ${document.pageCount}", style = with(Racha.type) { label.numeric() })
            RIconButton(RachaIcons.ZoomIn, stringResource(Res.string.zoom_in), { zoom = (zoom * ZOOM_STEP).coerceAtMost(MAX_ZOOM) })
        }
    }
}

@Composable
private fun Page(document: PdfDocument, index: Int, width: Dp) {
    val pixels = with(LocalDensity.current) { width.roundToPx() }.coerceAtMost(MAX_RENDER_PX)
    val bitmap by produceState<ImageBitmap?>(null, document, index, pixels) {
        value = runCatching { document.render(index, pixels) }.getOrNull()
    }
    Box(Modifier.width(width).aspectRatio(document.pageAspect(index)).background(Color.White)) {
        val page = bitmap
        if (page == null) SkeletonBlock(Modifier.fillMaxSize().padding(Racha.space.xl))
        else Image(page, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
    }
}

/** Only a safety bound for enormous windows; normally the page is as wide as the viewer. */
private val MAX_PAGE_WIDTH = 3000.dp
private const val ZOOM_STEP = 1.25f
private const val MIN_ZOOM = 0.5f
private const val MAX_ZOOM = 4f

/** Upper bound for one rendered page (memory): 4K wide at most. */
private const val MAX_RENDER_PX = 3840
