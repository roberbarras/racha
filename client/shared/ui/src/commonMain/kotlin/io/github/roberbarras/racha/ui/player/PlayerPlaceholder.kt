package io.github.roberbarras.racha.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import io.github.roberbarras.racha.application.browse.PlayableContext
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.playback.StartMode
import io.github.roberbarras.racha.domain.playback.PlaybackRules
import io.github.roberbarras.racha.ui.app.RachaDependencies
import io.github.roberbarras.racha.ui.common.timecode
import io.github.roberbarras.racha.ui.design.MessageState
import io.github.roberbarras.racha.ui.design.RIconButton
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.RachaIcons
import io.github.roberbarras.racha.ui.resources.Res
import io.github.roberbarras.racha.ui.resources.back
import io.github.roberbarras.racha.ui.resources.player_coming
import io.github.roberbarras.racha.ui.resources.resource_coming
import org.jetbrains.compose.resources.stringResource

/** Where a platform has no player yet (Linux until H6). */
@Composable
fun PlayerPlaceholder(deps: RachaDependencies, element: ContentId, mode: StartMode, onBack: () -> Unit) {
    Placeholder(deps, element, onBack) { c ->
        val start = if (mode == StartMode.BEGINNING) 0 else PlaybackRules.resumePosition(c.progress) ?: 0
        stringResource(Res.string.player_coming, timecode(start))
    }
}

/** Resources (HTML/URL/TXT/PDF) open in the viewer that arrives in H7. */
@Composable
fun ResourcePlaceholder(deps: RachaDependencies, element: ContentId, onBack: () -> Unit) {
    Placeholder(deps, element, onBack) { stringResource(Res.string.resource_coming) }
}

@Composable
private fun Placeholder(deps: RachaDependencies, element: ContentId, onBack: () -> Unit, message: @Composable (PlayableContext) -> String) {
    val context by produceState<PlayableContext?>(null, element) { value = deps.getElement(element) }
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(Racha.space.xxl),
        verticalArrangement = Arrangement.spacedBy(Racha.space.xl),
    ) {
        RIconButton(RachaIcons.ArrowLeft, stringResource(Res.string.back), onBack)
        context?.let { c -> MessageState(RachaIcons.Clapperboard, c.element.displayTitle, message(c)) }
    }
}
