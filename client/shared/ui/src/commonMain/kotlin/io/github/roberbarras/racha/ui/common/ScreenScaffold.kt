package io.github.roberbarras.racha.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.roberbarras.racha.application.library.ElementInItem
import io.github.roberbarras.racha.ui.design.RIcon
import io.github.roberbarras.racha.ui.design.RText
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.interactive

@Composable
fun ScreenTitle(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(Racha.space.xs)) {
        RText(title, style = Racha.type.display)
        subtitle?.let { RText(it, style = Racha.type.body, color = Racha.colors.inkMuted) }
    }
}

/** A bordered list of lesson/episode rows with their course; tapping opens the course. */
@Composable
fun ElementList(rows: List<ElementInItem>, trailing: @Composable (ElementInItem) -> String? = { null }, onOpen: (ElementInItem) -> Unit) {
    val shape = RoundedCornerShape(Racha.radius.l)
    Column(Modifier.fillMaxWidth().clip(shape).background(Racha.colors.surface).border(1.dp, Racha.colors.border, shape)) {
        rows.forEachIndexed { index, row ->
            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Racha.colors.border))
            Row(
                Modifier.fillMaxWidth().interactive({ onOpen(row) }).padding(horizontal = Racha.space.l, vertical = Racha.space.m),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Racha.space.m),
            ) {
                RIcon(elementIcon(row.element), null, tint = Racha.colors.inkMuted)
                Column(Modifier.weight(1f)) {
                    RText(row.element.displayTitle, style = Racha.type.bodyStrong, maxLines = 1)
                    RText(itemTitle(row.item), style = Racha.type.caption, color = Racha.colors.inkMuted, maxLines = 1)
                }
                trailing(row)?.let { RText(it, style = with(Racha.type) { caption.numeric() }, color = Racha.colors.inkMuted) }
            }
        }
    }
}
