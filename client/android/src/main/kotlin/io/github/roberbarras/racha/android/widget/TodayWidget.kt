package io.github.roberbarras.racha.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.core.net.toUri
import io.github.roberbarras.racha.android.MainActivity
import io.github.roberbarras.racha.android.R
import io.github.roberbarras.racha.android.RachaApplication
import io.github.roberbarras.racha.application.browse.TodayCard
import io.github.roberbarras.racha.ui.app.DeepLink
import io.github.roberbarras.racha.ui.notifications.WidgetTexts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The home-screen widget (spec §10.5). Plain RemoteViews: nothing runs for it in the background; the app pushes a new
 * state when the streak or today's turn change, and the system asks every 6 h (a new day can end a streak).
 */
class TodayWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val graph = (context.applicationContext as RachaApplication).graph
                render(context, graph.observeTodayCard().first())
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** Redraws every placed widget; nothing to do (and nothing read) when none is placed. */
        suspend fun render(context: Context, card: TodayCard) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TodayWidget::class.java))
            if (ids.isEmpty()) return
            val text = WidgetTexts.of(card)
            val views = RemoteViews(context.packageName, R.layout.widget_today).apply {
                setTextViewText(R.id.widget_streak, text.streak)
                setTextViewText(R.id.widget_label, text.label)
                setTextViewText(R.id.widget_title, text.title)
                setTextViewText(R.id.widget_subtitle, text.subtitle)
                setViewVisibility(R.id.widget_subtitle, if (text.subtitle.isEmpty()) View.GONE else View.VISIBLE)
                setViewVisibility(R.id.widget_progress, if (card.element == null) View.GONE else View.VISIBLE)
                setProgressBar(R.id.widget_progress, 100, card.itemProgressPct.coerceIn(0, 100), false)
                setTextViewText(R.id.widget_action, text.action)
                val open = open(context, card.element?.let(DeepLink::play), requestCode = 1)
                setOnClickPendingIntent(R.id.widget_action, open)
                setOnClickPendingIntent(R.id.widget_root, open(context, null, requestCode = 2)) // the rest opens Home
            }
            manager.updateAppWidget(ids, views)
        }

        private fun open(context: Context, link: String?, requestCode: Int): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                link?.let { data = it.toUri() }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
