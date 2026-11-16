package io.github.roberbarras.racha.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.roberbarras.racha.domain.library.Category
import io.github.roberbarras.racha.ui.app.RachaDependencies
import io.github.roberbarras.racha.ui.common.IconBadge
import io.github.roberbarras.racha.ui.common.categoryIcon
import io.github.roberbarras.racha.ui.common.categoryName
import io.github.roberbarras.racha.ui.design.ButtonKind
import io.github.roberbarras.racha.ui.design.RButton
import io.github.roberbarras.racha.ui.design.RIconButton
import io.github.roberbarras.racha.ui.design.RText
import io.github.roberbarras.racha.ui.design.RTextField
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.RachaIcons
import io.github.roberbarras.racha.ui.design.Tag
import io.github.roberbarras.racha.ui.design.TagTone
import io.github.roberbarras.racha.ui.resources.Res
import io.github.roberbarras.racha.ui.resources.category_add
import io.github.roberbarras.racha.ui.resources.category_delete
import io.github.roberbarras.racha.ui.resources.category_delete_confirm
import io.github.roberbarras.racha.ui.resources.category_excludes
import io.github.roberbarras.racha.ui.resources.category_in_leisure
import io.github.roberbarras.racha.ui.resources.category_in_study
import io.github.roberbarras.racha.ui.resources.category_make_primary
import io.github.roberbarras.racha.ui.resources.category_move_down
import io.github.roberbarras.racha.ui.resources.category_move_up
import io.github.roberbarras.racha.ui.resources.category_name
import io.github.roberbarras.racha.ui.resources.category_new_placeholder
import io.github.roberbarras.racha.ui.resources.category_primary
import io.github.roberbarras.racha.ui.resources.manage_folders
import io.github.roberbarras.racha.ui.resources.section_categories
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Settings > Categories (spec §9 "Categorías" and "Modo"). Folders have their own screen. */
@Composable
fun CategoriesSection(deps: RachaDependencies, onFolders: () -> Unit) = SettingsSection(stringResource(Res.string.section_categories), RachaIcons.Layers) {
    val all by remember { deps.categories.observeAll() }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val manage = deps.manageCategories
    val ordered = all.sortedBy { it.order }
    ordered.forEachIndexed { index, category ->
        if (index > 0) Hairline()
        CategoryRow(
            category,
            first = index == 0,
            last = index == ordered.lastIndex,
            onRename = { name -> scope.launch { manage.rename(category.key, name) } },
            onPrimary = { scope.launch { manage.setPrimary(category.key) } },
            onVisibility = { study, leisure -> scope.launch { manage.setVisibility(category.key, study, leisure) } },
            onMove = { direction -> scope.launch { manage.move(category.key, direction) } },
            onExcludes = { patterns -> scope.launch { manage.setExcludes(category.key, patterns) } },
            onDelete = { scope.launch { manage.delete(category.key) } },
        )
    }
    Hairline()
    var name by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Racha.space.m)) {
        RTextField(
            name, { name = it }, stringResource(Res.string.category_new_placeholder), Modifier.weight(1f),
            imeAction = ImeAction.Done, onSubmit = { if (name.isNotBlank()) scope.launch { manage.create(name); name = "" } },
        )
        RButton(stringResource(Res.string.category_add), { scope.launch { manage.create(name); name = "" } }, enabled = name.isNotBlank(), icon = RachaIcons.Plus)
    }
    RButton(stringResource(Res.string.manage_folders), onFolders, kind = ButtonKind.Secondary, icon = RachaIcons.FolderOpen)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryRow(
    category: Category,
    first: Boolean,
    last: Boolean,
    onRename: (String) -> Unit,
    onPrimary: () -> Unit,
    onVisibility: (study: Boolean, leisure: Boolean) -> Unit,
    onMove: (Int) -> Unit,
    onExcludes: (List<String>) -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val label = categoryName(category)
    Column(verticalArrangement = Arrangement.spacedBy(Racha.space.s)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Racha.space.s)) {
            IconBadge(categoryIcon(category))
            RText(label, style = Racha.type.bodyStrong, modifier = Modifier.weight(1f), maxLines = 1)
            if (category.isPrimary) Tag(stringResource(Res.string.category_primary), tone = TagTone.Accent)
            RIconButton(RachaIcons.ChevronLeft, stringResource(Res.string.category_move_up) + " · " + label, { if (!first) onMove(-1) },
                tint = if (first) Racha.colors.inkSubtle else Racha.colors.ink, modifier = Modifier.rotate(90f)) // chevrons left/right turned into up/down
            RIconButton(RachaIcons.ChevronRight, stringResource(Res.string.category_move_down) + " · " + label, { if (!last) onMove(1) },
                tint = if (last) Racha.colors.inkSubtle else Racha.colors.ink, modifier = Modifier.rotate(90f)) // chevrons left/right turned into up/down
            RIconButton(if (expanded) RachaIcons.ChevronDown else RachaIcons.Pencil, label, { expanded = !expanded })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Racha.space.s), verticalArrangement = Arrangement.spacedBy(Racha.space.s)) {
            RButton(
                stringResource(Res.string.category_in_study), { onVisibility(!category.visibleInStudy, category.visibleInLeisure) },
                kind = if (category.visibleInStudy) ButtonKind.Primary else ButtonKind.Secondary,
            )
            RButton(
                stringResource(Res.string.category_in_leisure), { onVisibility(category.visibleInStudy, !category.visibleInLeisure) },
                kind = if (category.visibleInLeisure) ButtonKind.Primary else ButtonKind.Secondary,
            )
            if (!category.isPrimary) RButton(stringResource(Res.string.category_make_primary), onPrimary, kind = ButtonKind.Ghost)
        }
        if (expanded) {
            TextRow(stringResource(Res.string.category_name), category.name ?: "", { onRename(it) }, description = if (category.key.isBuiltIn) label else null)
            TextRow(stringResource(Res.string.category_excludes), category.excludePatterns.joinToString(", "), { text ->
                onExcludes(text.split(',', '\n'))
            })
            if (!category.key.isBuiltIn) {
                if (deleting) {
                    ConfirmBox(stringResource(Res.string.category_delete_confirm), onConfirm = { deleting = false; onDelete() }, onCancel = { deleting = false }, danger = true)
                } else {
                    RButton(stringResource(Res.string.category_delete), { deleting = true }, kind = ButtonKind.Secondary, icon = RachaIcons.Trash2)
                }
            }
        }
    }
}
