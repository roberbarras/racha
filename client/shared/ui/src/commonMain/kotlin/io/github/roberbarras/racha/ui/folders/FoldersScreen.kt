package io.github.roberbarras.racha.ui.folders

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.roberbarras.racha.domain.library.Category
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.CategoryRoot
import io.github.roberbarras.racha.ui.app.AppViewModel
import io.github.roberbarras.racha.ui.app.RachaDependencies
import io.github.roberbarras.racha.ui.common.IconBadge
import io.github.roberbarras.racha.ui.common.ScreenTitle
import io.github.roberbarras.racha.ui.common.StorageAccessBanner
import io.github.roberbarras.racha.ui.common.categoryIcon
import io.github.roberbarras.racha.ui.common.categoryName
import io.github.roberbarras.racha.ui.design.ButtonKind
import io.github.roberbarras.racha.ui.design.RButton
import io.github.roberbarras.racha.ui.design.RIconButton
import io.github.roberbarras.racha.ui.design.RText
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.RachaIcons
import io.github.roberbarras.racha.ui.resources.Res
import io.github.roberbarras.racha.ui.resources.add_folder
import io.github.roberbarras.racha.ui.resources.folders_message
import io.github.roberbarras.racha.ui.resources.folders_title
import io.github.roberbarras.racha.ui.resources.no_folders
import io.github.roberbarras.racha.ui.resources.remove_folder
import io.github.roberbarras.racha.ui.resources.scan_now
import io.github.roberbarras.racha.ui.resources.scan_report
import io.github.roberbarras.racha.ui.resources.scanning
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

data class CategoryFolders(val category: Category, val roots: List<CategoryRoot>)

class FoldersViewModel(private val deps: RachaDependencies) : ViewModel() {
    private val refresh = MutableStateFlow(0)
    val folders: StateFlow<List<CategoryFolders>> = combine(deps.categories.observeAll(), refresh) { all, _ ->
        all.map { CategoryFolders(it, deps.manageRoots.roots(it.key)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(category: CategoryKey) = viewModelScope.launch {
        val location = deps.platform.pickFolder() ?: return@launch
        deps.manageRoots.add(category, location)
        refresh.value++
    }

    fun remove(root: CategoryRoot) = viewModelScope.launch {
        deps.manageRoots.remove(root)
        refresh.value++
    }
}

/** Folders per category (spec §9 "Categorías": several roots each) and "Scan now" (§3.3). */
@Composable
fun FoldersScreen(deps: RachaDependencies, app: AppViewModel) {
    val vm = viewModel { FoldersViewModel(deps) }
    val folders by vm.folders.collectAsStateWithLifecycle()
    val scan by app.scan.collectAsStateWithLifecycle()
    val storageAccess by app.storageAccess.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Racha.space.xxl), verticalArrangement = Arrangement.spacedBy(Racha.space.xl)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { ScreenTitle(stringResource(Res.string.folders_title), stringResource(Res.string.folders_message)) }
                RButton(
                    stringResource(if (scan.running) Res.string.scanning else Res.string.scan_now),
                    onClick = { app.scanNow() },
                    enabled = !scan.running,
                    kind = ButtonKind.Secondary,
                    icon = RachaIcons.RefreshCw,
                )
            }
        }
        scan.last?.let { report ->
            item { RText(stringResource(Res.string.scan_report, report.added, report.updated, report.archived), style = Racha.type.caption, color = Racha.colors.inkMuted) }
        }
        if (!storageAccess) item { StorageAccessBanner(onAllow = app::requestStorageAccess) }
        folders.forEach { entry ->
            item(key = entry.category.key.value) { CategoryFoldersCard(entry, onAdd = { vm.add(entry.category.key) }, onRemove = vm::remove) }
        }
    }
}

@Composable
private fun CategoryFoldersCard(entry: CategoryFolders, onAdd: () -> Unit, onRemove: (CategoryRoot) -> Unit) {
    val shape = RoundedCornerShape(Racha.radius.l)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Racha.colors.surface).border(1.dp, Racha.colors.border, shape).padding(Racha.space.l),
        verticalArrangement = Arrangement.spacedBy(Racha.space.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Racha.space.m)) {
            IconBadge(categoryIcon(entry.category))
            RText(categoryName(entry.category), style = Racha.type.heading, modifier = Modifier.weight(1f))
            RButton(stringResource(Res.string.add_folder), onAdd, kind = ButtonKind.Secondary, icon = RachaIcons.FolderPlus)
        }
        if (entry.roots.isEmpty()) {
            RText(stringResource(Res.string.no_folders), style = Racha.type.body, color = Racha.colors.inkSubtle)
        }
        entry.roots.forEach { root ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(Racha.colors.border))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Racha.space.m)) {
                RText(displayPath(root.location), style = Racha.type.body, modifier = Modifier.weight(1f), maxLines = 2)
                RIconButton(RachaIcons.Trash2, stringResource(Res.string.remove_folder), { onRemove(root) }, tint = Racha.colors.inkMuted)
            }
        }
    }
}

/** `file:///home/leo/Cursos/` → `/home/leo/Cursos`, decoding %20 and friends. */
internal fun displayPath(location: String): String {
    val raw = location.removePrefix("file://").trimEnd('/')
    val bytes = buildList {
        var i = 0
        while (i < raw.length) {
            if (raw[i] == '%' && i + 2 < raw.length) {
                add(raw.substring(i + 1, i + 3).toInt(16).toByte()); i += 3
            } else {
                raw[i].toString().encodeToByteArray().forEach(::add); i++
            }
        }
    }
    return bytes.toByteArray().decodeToString()
}
