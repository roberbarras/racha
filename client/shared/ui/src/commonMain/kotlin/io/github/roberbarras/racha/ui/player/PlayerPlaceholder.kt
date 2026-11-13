package io.github.roberbarras.racha.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import io.github.roberbarras.racha.application.browse.PlayableContext
import io.github.roberbarras.racha.ui.app.RachaDependencies
import io.github.roberbarras.racha.ui.common.timecode
import androidx.compose.ui.Modifier
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.ui.design.MessageState
import io.github.roberbarras.racha.ui.design.RIconButton
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.RachaIcons
import io.github.roberbarras.racha.ui.resources.Res
import io.github.roberbarras.racha.ui.resources.back
import io.github.roberbarras.racha.ui.resources.player_coming
import org.jetbrains.compose.resources.stringResource

/** Navigation target for playback; the real players arrive in H5 (Android) and H6 (Linux). */
@Composable
fun PlayerPlaceholder(deps: RachaDependencies, element: ContentId, fromStart: Boolean, onBack: () -> Unit) {
    val context by produceState<PlayableContext?>(null, element) { value = deps.getElement(element) }
    Column(Modifier.fillMaxSize().padding(Racha.space.xxl), verticalArrangement = Arrangement.spacedBy(Racha.space.xl)) {
        RIconButton(RachaIcons.ArrowLeft, stringResource(Res.string.back), onBack)
        context?.let { c ->
            val start = if (fromStart) 0 else c.progress?.positionSec ?: 0
            MessageState(RachaIcons.Clapperboard, c.element.displayTitle, stringResource(Res.string.player_coming, timecode(start)))
        }
    }
}
