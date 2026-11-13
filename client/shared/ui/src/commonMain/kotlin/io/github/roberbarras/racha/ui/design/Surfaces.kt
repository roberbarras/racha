package io.github.roberbarras.racha.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Bordered paper card. [selected] outlines it in the accent (the item the detail pane is showing). */
@Composable
fun RCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    onClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Racha.colors
    val shape = RoundedCornerShape(Racha.radius.l)
    Column(
        modifier = modifier
            .clip(shape)
            .background(colors.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.accent else colors.border, shape)
            .then(if (onClick != null) Modifier.interactive(onClick, shape, onClickLabel = onClickLabel) else Modifier),
        content = content,
    )
}

/** Empty, error and offline states share one calm layout: icon, title, message, optional action (spec §10.3). */
@Composable
fun MessageState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    val colors = Racha.colors
    Column(
        modifier = modifier.fillMaxWidth().padding(Racha.space.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Racha.space.m),
    ) {
        Box(
            Modifier.clip(RoundedCornerShape(Racha.radius.l)).background(colors.accentSoft).padding(Racha.space.l),
        ) { RIcon(icon, null, tint = colors.accent, size = 28.dp) }
        RText(title, style = Racha.type.heading, textAlign = TextAlign.Center)
        RText(message, modifier = Modifier.widthIn(max = 420.dp), style = Racha.type.body, color = colors.inkMuted, textAlign = TextAlign.Center)
        if (actionLabel != null) RButton(actionLabel, onAction)
    }
}

/** Loading placeholder: soft blocks that pulse (static when the system reduces motion). */
@Composable
fun SkeletonBlock(modifier: Modifier = Modifier) {
    val reduce = Racha.reduceMotion
    val pulse = if (reduce) {
        0.6f
    } else {
        val transition = rememberInfiniteTransition()
        val value by transition.animateFloat(0.45f, 0.8f, infiniteRepeatable(tween(900), RepeatMode.Reverse))
        value
    }
    Box(modifier.alpha(pulse).clip(RoundedCornerShape(Racha.radius.m)).background(Racha.colors.surfaceSunken))
}

@Composable
fun ScreenBackground(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Racha.colors.background)) { content() }
}
