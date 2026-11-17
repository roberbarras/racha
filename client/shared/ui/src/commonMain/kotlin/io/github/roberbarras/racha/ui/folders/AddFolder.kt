package io.github.roberbarras.racha.ui.folders

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import io.github.roberbarras.racha.application.library.scan.FolderPreview
import io.github.roberbarras.racha.application.library.scan.PreviewItem
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.RootLayout
import io.github.roberbarras.racha.ui.design.ButtonKind
import io.github.roberbarras.racha.ui.design.RButton
import io.github.roberbarras.racha.ui.design.RText
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.RachaIcons
import io.github.roberbarras.racha.ui.resources.Res
import io.github.roberbarras.racha.ui.resources.add_library
import io.github.roberbarras.racha.ui.resources.add_single_item
import io.github.roberbarras.racha.ui.resources.cancel
import io.github.roberbarras.racha.ui.resources.change_layout_message
import io.github.roberbarras.racha.ui.resources.change_layout_title
import io.github.roberbarras.racha.ui.resources.folder_preview_add
import io.github.roberbarras.racha.ui.resources.folder_preview_as_library
import io.github.roberbarras.racha.ui.resources.folder_preview_as_single_item
import io.github.roberbarras.racha.ui.resources.folder_preview_elements
import io.github.roberbarras.racha.ui.resources.folder_preview_empty
import io.github.roberbarras.racha.ui.resources.folder_preview_guess_single_item
import io.github.roberbarras.racha.ui.resources.folder_preview_library
import io.github.roberbarras.racha.ui.resources.folder_preview_more
import io.github.roberbarras.racha.ui.resources.folder_preview_reading
import io.github.roberbarras.racha.ui.resources.folder_preview_sections
import io.github.roberbarras.racha.ui.resources.folder_preview_single_item
import io.github.roberbarras.racha.ui.resources.layout_auto
import io.github.roberbarras.racha.ui.resources.layout_library
import io.github.roberbarras.racha.ui.resources.layout_library_hint
import io.github.roberbarras.racha.ui.resources.layout_single_item
import io.github.roberbarras.racha.ui.resources.layout_single_item_hint
import io.github.roberbarras.racha.ui.resources.loose_files
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** A folder picked but not added yet: the user sees what it would become first (ADR-0013). [preview] null: reading. */
data class PendingFolder(val category: CategoryKey, val location: String, val layout: RootLayout, val preview: FolderPreview? = null)

/** The two ways of adding a folder: several courses or series, or one (ADR-0013). */
@Composable
fun AddFolderButtons(onAdd: (RootLayout) -> Unit, enabled: Boolean = true) {
    RButton(stringResource(Res.string.add_library), { onAdd(RootLayout.LIBRARY) }, kind = ButtonKind.Secondary, icon = RachaIcons.Layers, enabled = enabled)
    RButton(stringResource(Res.string.add_single_item), { onAdd(RootLayout.SINGLE_ITEM) }, kind = ButtonKind.Secondary, icon = RachaIcons.FolderPlus, enabled = enabled)
}

@Composable
fun layoutName(layout: RootLayout): String = stringResource(
    when (layout) {
        RootLayout.LIBRARY -> Res.string.layout_library
        RootLayout.SINGLE_ITEM -> Res.string.layout_single_item
        RootLayout.AUTO -> Res.string.layout_auto
    },
)

/** What the picked folder would become with the chosen layout, with a way to switch to the other one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FolderPreviewCard(pending: PendingFolder, onLayout: (RootLayout) -> Unit, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Panel {
        RText(layoutName(pending.layout), style = Racha.type.bodyStrong)
        RText(displayPath(pending.location), style = Racha.type.caption, color = Racha.colors.inkMuted, maxLines = 2)
        RText(
            stringResource(if (pending.layout == RootLayout.SINGLE_ITEM) Res.string.layout_single_item_hint else Res.string.layout_library_hint),
            style = Racha.type.caption, color = Racha.colors.inkMuted,
        )
        val preview = pending.preview
        if (preview == null) {
            RText(stringResource(Res.string.folder_preview_reading), style = Racha.type.body, color = Racha.colors.inkMuted)
        } else {
            Outcome(preview, pending.layout)
            // Only section-like names (`NN - Title`, seasons, dates) are evidence; plain names say nothing either way.
            if (pending.layout == RootLayout.LIBRARY && preview.guess == RootLayout.SINGLE_ITEM) {
                RText(stringResource(Res.string.folder_preview_guess_single_item), style = Racha.type.body)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Racha.space.s), verticalArrangement = Arrangement.spacedBy(Racha.space.s)) {
            RButton(stringResource(Res.string.folder_preview_add), onConfirm, icon = RachaIcons.Check, enabled = preview != null)
            val switchTo = if (pending.layout == RootLayout.SINGLE_ITEM) RootLayout.LIBRARY else RootLayout.SINGLE_ITEM
            RButton(
                stringResource(if (switchTo == RootLayout.SINGLE_ITEM) Res.string.folder_preview_as_single_item else Res.string.folder_preview_as_library),
                { onLayout(switchTo) }, kind = ButtonKind.Secondary,
            )
            RButton(stringResource(Res.string.cancel), onCancel, kind = ButtonKind.Ghost)
        }
    }
}

/** "What does this folder hold?" for a folder already added: changing it rescans it, progress is kept. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LayoutChooser(current: RootLayout, onChoose: (RootLayout) -> Unit, onCancel: () -> Unit) {
    Panel {
        RText(stringResource(Res.string.change_layout_title), style = Racha.type.bodyStrong)
        RText(stringResource(Res.string.change_layout_message), style = Racha.type.caption, color = Racha.colors.inkMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Racha.space.s), verticalArrangement = Arrangement.spacedBy(Racha.space.s)) {
            listOf(RootLayout.LIBRARY to RachaIcons.Layers, RootLayout.SINGLE_ITEM to RachaIcons.FolderOpen).forEach { (layout, icon) ->
                RButton(layoutName(layout), { onChoose(layout) }, kind = if (layout == current) ButtonKind.Primary else ButtonKind.Secondary, icon = icon)
            }
            RButton(stringResource(Res.string.cancel), onCancel, kind = ButtonKind.Ghost)
        }
    }
}

@Composable
private fun Outcome(preview: FolderPreview, layout: RootLayout) {
    val items = preview.items(layout)
    when {
        items.isEmpty() -> RText(stringResource(Res.string.folder_preview_empty), style = Racha.type.body, color = Racha.colors.inkMuted)
        layout == RootLayout.SINGLE_ITEM -> {
            val item = items.single()
            RText(stringResource(Res.string.folder_preview_single_item, item.title), style = Racha.type.body)
            RText(counts(item), style = Racha.type.caption, color = Racha.colors.inkMuted)
        }
        else -> {
            RText(pluralStringResource(Res.plurals.folder_preview_library, items.size, items.size), style = Racha.type.body)
            val loose = stringResource(Res.string.loose_files)
            val names = items.take(MAX_NAMES).map { it.title.ifEmpty { loose } }
            val more = items.size - names.size
            RText(
                names.joinToString(" · ") + if (more > 0) " " + stringResource(Res.string.folder_preview_more, more) else "",
                style = Racha.type.caption, color = Racha.colors.inkMuted, maxLines = 3,
            )
        }
    }
}

@Composable
private fun counts(item: PreviewItem): String =
    pluralStringResource(Res.plurals.folder_preview_sections, item.sections, item.sections) + " · " +
        pluralStringResource(Res.plurals.folder_preview_elements, item.elements, item.elements)

@Composable
private fun Panel(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(Racha.radius.m)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Racha.colors.accentSoft).padding(Racha.space.l),
        verticalArrangement = Arrangement.spacedBy(Racha.space.s),
    ) { content() }
}

private const val MAX_NAMES = 6
