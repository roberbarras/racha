package io.github.roberbarras.racha.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.roberbarras.racha.ui.design.RButton
import io.github.roberbarras.racha.ui.design.RText
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.RachaIcons
import io.github.roberbarras.racha.ui.resources.Res
import io.github.roberbarras.racha.ui.resources.permission_action
import io.github.roberbarras.racha.ui.resources.permission_message
import io.github.roberbarras.racha.ui.resources.permission_title
import org.jetbrains.compose.resources.stringResource

/** Android without "All files access" (ADR-0005): explains why and opens the system screen. */
@Composable
fun StorageAccessBanner(onAllow: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Racha.radius.l)
    Row(
        modifier.fillMaxWidth().clip(shape).background(Racha.colors.accentSoft).border(1.dp, Racha.colors.accent.copy(alpha = 0.4f), shape)
            .padding(Racha.space.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Racha.space.l),
    ) {
        IconBadge(RachaIcons.HardDrive)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Racha.space.xs)) {
            RText(stringResource(Res.string.permission_title), style = Racha.type.heading)
            RText(stringResource(Res.string.permission_message), style = Racha.type.body, color = Racha.colors.inkMuted)
        }
        RButton(stringResource(Res.string.permission_action), onAllow)
    }
}
