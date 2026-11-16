package io.github.roberbarras.racha.ui.notifications

import io.github.roberbarras.racha.application.browse.TodayCard
import io.github.roberbarras.racha.ui.resources.Res
import io.github.roberbarras.racha.ui.resources.recommended_today
import io.github.roberbarras.racha.ui.resources.streak_days
import io.github.roberbarras.racha.ui.resources.streak_none
import io.github.roberbarras.racha.ui.resources.widget_continue
import io.github.roberbarras.racha.ui.resources.widget_empty
import io.github.roberbarras.racha.ui.resources.widget_open
import org.jetbrains.compose.resources.getString

/** The widget's words in the current language. */
data class WidgetText(val streak: String, val label: String, val title: String, val subtitle: String, val action: String)

object WidgetTexts {
    suspend fun of(card: TodayCard): WidgetText = WidgetText(
        streak = if (card.streakDays > 0) getString(Res.string.streak_days, card.streakDays) else getString(Res.string.streak_none),
        label = getString(Res.string.recommended_today).uppercase(),
        title = card.elementTitle ?: getString(Res.string.widget_empty),
        subtitle = card.itemTitle.orEmpty(),
        action = getString(if (card.element != null) Res.string.widget_continue else Res.string.widget_open),
    )
}
