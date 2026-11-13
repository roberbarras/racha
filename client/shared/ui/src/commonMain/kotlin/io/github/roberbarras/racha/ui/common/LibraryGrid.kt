package io.github.roberbarras.racha.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.roberbarras.racha.domain.library.Category
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.LibraryItem
import io.github.roberbarras.racha.domain.library.StructureKind
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.resources.Res
import io.github.roberbarras.racha.ui.resources.loose_files
import org.jetbrains.compose.resources.stringResource

/** Responsive grid of course/series cards (≈240 dp columns), laid out inside a parent scroll. */
@Composable
fun ItemGrid(items: List<LibraryItem>, categories: Map<CategoryKey, Category>, onOpen: (LibraryItem) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = (maxWidth / 240.dp).toInt().coerceIn(1, 6)
        Column(verticalArrangement = Arrangement.spacedBy(Racha.space.l)) {
            items.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Racha.space.l)) {
                    row.forEach { item ->
                        val tint = categories[item.categoryKey]?.color?.let(::parseColor) ?: Racha.colors.accent
                        ItemCard(item, itemTitle(item), tint, onClick = { onOpen(item) }, modifier = Modifier.weight(1f))
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** User title, detected title, or the localized "Loose files" for the root's loose item. */
@Composable
fun itemTitle(item: LibraryItem): String =
    if (item.kind == StructureKind.LOOSE && item.titleOverride == null) stringResource(Res.string.loose_files) else item.displayTitle
