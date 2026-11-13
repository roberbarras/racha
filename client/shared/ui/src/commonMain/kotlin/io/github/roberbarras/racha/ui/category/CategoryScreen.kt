package io.github.roberbarras.racha.ui.category

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.ui.app.RachaDependencies
import io.github.roberbarras.racha.ui.common.ItemGrid
import io.github.roberbarras.racha.ui.common.ScreenTitle
import io.github.roberbarras.racha.ui.common.categoryName
import io.github.roberbarras.racha.ui.design.RIconButton
import io.github.roberbarras.racha.ui.design.RText
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.RachaIcons
import io.github.roberbarras.racha.ui.resources.Res
import io.github.roberbarras.racha.ui.resources.back
import io.github.roberbarras.racha.ui.resources.empty_category
import org.jetbrains.compose.resources.stringResource

/** All courses/series of one category (sidebar "Categories"). */
@Composable
fun CategoryScreen(deps: RachaDependencies, key: String, onBack: () -> Unit, onOpenItem: (ContentId) -> Unit) {
    val view by remember(key) { deps.observeCategory(CategoryKey(key)) }.collectAsStateWithLifecycle(null)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Racha.space.xxl), verticalArrangement = Arrangement.spacedBy(Racha.space.xl)) {
        item { RIconButton(RachaIcons.ArrowLeft, stringResource(Res.string.back), onBack) }
        view?.let { v ->
            item { ScreenTitle(categoryName(v.category)) }
            if (v.items.isEmpty()) item { RText(stringResource(Res.string.empty_category), style = Racha.type.body, color = Racha.colors.inkMuted) }
            else item { ItemGrid(v.items, mapOf(v.category.key to v.category), onOpen = { onOpenItem(it.id) }) }
        }
    }
}
