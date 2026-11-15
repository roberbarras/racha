package io.github.roberbarras.racha.ui.resource

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.roberbarras.racha.application.resource.ResourceContent
import io.github.roberbarras.racha.application.resource.ResourceState
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.library.PlayableElement
import io.github.roberbarras.racha.domain.resource.TextSegment
import io.github.roberbarras.racha.ui.app.RachaDependencies
import io.github.roberbarras.racha.ui.common.CourseQueueList
import io.github.roberbarras.racha.ui.common.elementIcon
import io.github.roberbarras.racha.ui.design.ButtonKind
import io.github.roberbarras.racha.ui.design.MessageState
import io.github.roberbarras.racha.ui.design.RButton
import io.github.roberbarras.racha.ui.design.RIcon
import io.github.roberbarras.racha.ui.design.RIconButton
import io.github.roberbarras.racha.ui.design.RText
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.RachaIcons
import io.github.roberbarras.racha.ui.design.SkeletonBlock
import io.github.roberbarras.racha.ui.resources.Res
import io.github.roberbarras.racha.ui.resources.back
import io.github.roberbarras.racha.ui.resources.elements
import io.github.roberbarras.racha.ui.resources.mark_watched
import io.github.roberbarras.racha.ui.resources.next_element
import io.github.roberbarras.racha.ui.resources.open_externally
import io.github.roberbarras.racha.ui.resources.open_in_browser
import io.github.roberbarras.racha.ui.resources.previous_element
import io.github.roberbarras.racha.ui.resources.resource_missing_message
import io.github.roberbarras.racha.ui.resources.resource_missing_title
import io.github.roberbarras.racha.ui.resources.resource_unsupported
import io.github.roberbarras.racha.ui.resources.shortcut_without_link
import io.github.roberbarras.racha.ui.resources.text_truncated
import io.github.roberbarras.racha.ui.resources.watched
import org.jetbrains.compose.resources.stringResource

/**
 * Resource viewer (spec §5): HTML, `.url`, `.txt` and PDF as course elements, full screen, with previous/next, a
 * watched mark (set on leaving or explicitly) and the course list on the right. [onOpenElement] opens a neighbour.
 */
@Composable
fun ResourceScreen(deps: RachaDependencies, element: ContentId, onBack: () -> Unit, onOpenElement: (PlayableElement) -> Unit) {
    val vm = viewModel(key = "resource-${element.value}") { ResourceViewModel(deps, element) }
    val state by vm.state.collectAsStateWithLifecycle()
    val sidebar by vm.sidebar.collectAsStateWithLifecycle()
    val hooks = deps.platform.resources
    BoxWithConstraints(Modifier.fillMaxSize().background(Racha.colors.background).safeDrawingPadding()) {
        val compact = maxWidth < Racha.layout.compactMaxWidth
        Column(Modifier.fillMaxSize()) {
            TopBar(state, compact, hooks, onBack, onOpenElement, onToggleWatched = { vm.setWatched(!state.watched) }, onToggleList = { vm.sidebar.value = !sidebar })
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) { Content(state, hooks) }
                if (sidebar && !compact) {
                    Column(Modifier.width(Racha.layout.detailPaneWidth).fillMaxHeight().background(Racha.colors.rail).padding(Racha.space.m)) {
                        CourseQueueList(state.queue, state.element?.id, onSelect = { onOpenElement(it.element) })
                    }
                }
            }
        }
        if (sidebar && compact) {
            Column(Modifier.fillMaxSize().background(Racha.colors.rail).padding(Racha.space.m)) {
                RIconButton(RachaIcons.ArrowLeft, stringResource(Res.string.back), { vm.sidebar.value = false }, tint = Racha.colors.onRail)
                CourseQueueList(state.queue, state.element?.id, onSelect = { onOpenElement(it.element) })
            }
        }
    }
}

@Composable
private fun TopBar(
    state: ResourceState,
    compact: Boolean,
    hooks: ResourceHooks?,
    onBack: () -> Unit,
    onOpenElement: (PlayableElement) -> Unit,
    onToggleWatched: () -> Unit,
    onToggleList: () -> Unit,
) {
    // On a phone the title gets its own row, like the player's top bar.
    Column(Modifier.fillMaxWidth().padding(horizontal = Racha.space.m, vertical = Racha.space.s)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Racha.space.xs)) {
            RIconButton(RachaIcons.ArrowLeft, stringResource(Res.string.back), onBack)
            state.element?.let { RIcon(elementIcon(it), null, tint = Racha.colors.inkMuted) }
            Column(Modifier.weight(1f).padding(horizontal = Racha.space.s)) {
                RText(state.element?.displayTitle.orEmpty(), style = Racha.type.heading, maxLines = if (compact) 2 else 1)
                RText(state.item?.displayTitle.orEmpty(), style = Racha.type.caption, color = Racha.colors.inkMuted, maxLines = 1)
            }
            if (!compact) Actions(state, compact, hooks, onOpenElement, onToggleWatched, onToggleList)
        }
        if (compact) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Actions(state, compact, hooks, onOpenElement, onToggleWatched, onToggleList)
            }
        }
    }
}

@Composable
private fun Actions(
    state: ResourceState,
    compact: Boolean,
    hooks: ResourceHooks?,
    onOpenElement: (PlayableElement) -> Unit,
    onToggleWatched: () -> Unit,
    onToggleList: () -> Unit,
) {
    val external = externalTarget(state.content)
    if (hooks != null && external != null) {
        RIconButton(RachaIcons.ExternalLink, stringResource(Res.string.open_externally), { external(hooks) })
    }
    state.previous?.let { RIconButton(RachaIcons.ChevronLeft, stringResource(Res.string.previous_element), { onOpenElement(it) }) }
    state.next?.let { RIconButton(RachaIcons.ChevronRight, stringResource(Res.string.next_element), { onOpenElement(it) }) }
    if (state.content != null && state.content != ResourceContent.Missing) {
        val label = stringResource(if (state.watched) Res.string.watched else Res.string.mark_watched)
        if (compact) {
            RIconButton(if (state.watched) RachaIcons.CircleCheck else RachaIcons.CircleDashed, label, onToggleWatched,
                tint = if (state.watched) Racha.colors.success else Racha.colors.ink)
        } else {
            RButton(label, onToggleWatched, kind = if (state.watched) ButtonKind.Ghost else ButtonKind.Secondary,
                icon = if (state.watched) RachaIcons.CircleCheck else RachaIcons.CircleDashed)
        }
    }
    RIconButton(RachaIcons.PanelRight, stringResource(Res.string.elements), onToggleList)
}

/** "Open outside the app": the link itself, or the file with the system's app. */
private fun externalTarget(content: ResourceContent?): ((ResourceHooks) -> Unit)? = when (content) {
    is ResourceContent.Link -> content.url?.let { url -> { hooks: ResourceHooks -> hooks.openExternal(url) } }
    is ResourceContent.Pdf -> { hooks -> hooks.openFile(content.path) }
    is ResourceContent.Html -> { hooks -> hooks.openFile(content.folder.trimEnd('/') + "/" + content.page.value) }
    else -> null
}

@Composable
private fun Content(state: ResourceState, hooks: ResourceHooks?) {
    when (val content = state.content) {
        null -> SkeletonBlock(Modifier.fillMaxSize().padding(Racha.space.xl))
        ResourceContent.Missing -> MessageState(RachaIcons.FileText, stringResource(Res.string.resource_missing_title), stringResource(Res.string.resource_missing_message))
        is ResourceContent.Html ->
            if (hooks == null) Unsupported()
            else hooks.HtmlView(content.folder, content.page, hooks::openExternal, Modifier.fillMaxSize())
        is ResourceContent.Link -> LinkCard(state, content, hooks)
        is ResourceContent.Text -> TextContent(content, hooks)
        is ResourceContent.Pdf -> if (hooks == null) Unsupported() else PdfViewer(hooks, content.path)
    }
}

@Composable
private fun Unsupported() = MessageState(RachaIcons.FileText, "", stringResource(Res.string.resource_unsupported))

@Composable
private fun LinkCard(state: ResourceState, content: ResourceContent.Link, hooks: ResourceHooks?) {
    Box(Modifier.fillMaxSize().padding(Racha.space.xl), contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(Racha.radius.l)
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().clip(shape).background(Racha.colors.surface).border(1.dp, Racha.colors.border, shape)
                .padding(Racha.space.xl),
            verticalArrangement = Arrangement.spacedBy(Racha.space.m),
        ) {
            RIcon(RachaIcons.Link, null, tint = Racha.colors.accent)
            RText(state.element?.displayTitle.orEmpty(), style = Racha.type.title)
            val url = content.url
            if (url == null) {
                RText(stringResource(Res.string.shortcut_without_link), color = Racha.colors.inkMuted)
            } else {
                SelectionContainer { RText(url, style = Racha.type.body, color = Racha.colors.inkMuted) }
                if (hooks != null) RButton(stringResource(Res.string.open_in_browser), { hooks.openExternal(url) }, icon = RachaIcons.ExternalLink)
            }
        }
    }
}

/** Plain text in reading width, selectable, with its links clickable (opened in the system browser). */
@Composable
private fun TextContent(content: ResourceContent.Text, hooks: ResourceHooks?) {
    val linkStyle = TextLinkStyles(SpanStyle(color = Racha.colors.accent, textDecoration = TextDecoration.Underline))
    // One item per paragraph keeps very long files light: only what is on screen is laid out.
    val paragraphs = remember(content) { paragraphsOf(content.segments) }
    val style = Racha.type.body.copy(color = Racha.colors.ink)
    SelectionContainer {
        LazyColumn(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (content.truncated) item {
                RText(stringResource(Res.string.text_truncated), style = Racha.type.caption, color = Racha.colors.inkMuted, modifier = Modifier.padding(Racha.space.l))
            }
            items(paragraphs) { paragraph ->
                val text = buildAnnotatedString {
                    paragraph.forEach { segment ->
                        when (segment) {
                            is TextSegment.Plain -> append(segment.text)
                            is TextSegment.Link -> withLink(LinkAnnotation.Url(segment.url, linkStyle) { hooks?.openExternal(segment.url) }) { append(segment.text) }
                        }
                    }
                }
                BasicText(text, Modifier.widthIn(max = Racha.layout.readingMaxWidth).fillMaxWidth().padding(horizontal = Racha.space.xl, vertical = Racha.space.xs), style = style)
            }
        }
    }
}

/** Splits segments at line breaks so each line/paragraph is its own list item. */
private fun paragraphsOf(segments: List<TextSegment>): List<List<TextSegment>> {
    val paragraphs = mutableListOf<MutableList<TextSegment>>(mutableListOf())
    segments.forEach { segment ->
        if (segment is TextSegment.Plain) {
            val lines = segment.text.replace("\r\n", "\n").split('\n')
            lines.forEachIndexed { i, line ->
                if (i > 0) paragraphs += mutableListOf<TextSegment>()
                if (line.isNotEmpty()) paragraphs.last() += TextSegment.Plain(line)
            }
        } else {
            paragraphs.last() += segment
        }
    }
    return paragraphs.map { it.ifEmpty { listOf(TextSegment.Plain(" ")) } }
}
