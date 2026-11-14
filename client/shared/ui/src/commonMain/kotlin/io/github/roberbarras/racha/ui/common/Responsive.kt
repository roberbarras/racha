package io.github.roberbarras.racha.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.max
import io.github.roberbarras.racha.ui.design.Racha

/**
 * A vertical list that works from a phone to a 4K monitor: normal gutters on small widths, and on wide windows the
 * gutters grow so the content never gets wider than [maxContentWidth] (long rows are hard to read and scan).
 * [content] receives whether the width is compact.
 */
@Composable
fun ResponsiveColumn(
    modifier: Modifier = Modifier.fillMaxSize(),
    maxContentWidth: Dp = Racha.layout.contentMaxWidth,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.(compact: Boolean) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val compact = maxWidth < Racha.layout.compactMaxWidth
        val base = if (compact) Racha.space.l else Racha.space.xxl
        val gutter = max(base, (maxWidth - maxContentWidth) / 2)
        LazyColumn(
            Modifier.fillMaxSize(),
            state = state,
            contentPadding = PaddingValues(horizontal = gutter, vertical = Racha.space.xl),
            verticalArrangement = Arrangement.spacedBy(Racha.space.xl),
        ) { content(compact) }
    }
}

/**
 * Width for cards in a horizontal row of [available] width: as many as fit between the min and max card width,
 * sized to fill the row exactly; on phones a fixed width that leaves the next card peeking out.
 */
fun rowCardWidth(available: Dp, gap: Dp, compact: Boolean): Dp {
    if (compact) return Racha.layout.cardWidth
    val columns = ((available + gap) / (Racha.layout.cardMinWidth + gap)).toInt().coerceAtLeast(1)
    val width = (available - gap * (columns - 1)) / columns
    return width.coerceAtMost(Racha.layout.cardMaxWidth)
}
