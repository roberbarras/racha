package io.github.roberbarras.racha.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.roberbarras.racha.application.stats.PersonalStats
import io.github.roberbarras.racha.domain.library.scan.CalendarDate
import io.github.roberbarras.racha.ui.common.compactDuration
import io.github.roberbarras.racha.ui.common.platformName
import io.github.roberbarras.racha.ui.design.RCard
import io.github.roberbarras.racha.ui.design.RText
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.SectionHeader
import io.github.roberbarras.racha.ui.resources.Res
import io.github.roberbarras.racha.ui.resources.by_device_title
import io.github.roberbarras.racha.ui.resources.by_hour_title
import io.github.roberbarras.racha.ui.resources.by_weekday_title
import io.github.roberbarras.racha.ui.resources.recent_title
import io.github.roberbarras.racha.ui.resources.sessions_title
import io.github.roberbarras.racha.ui.resources.stat_avg_session
import io.github.roberbarras.racha.ui.resources.stat_avg_speed
import io.github.roberbarras.racha.ui.resources.stat_sessions
import io.github.roberbarras.racha.ui.resources.stat_with_subtitles
import io.github.roberbarras.racha.ui.resources.titles_title
import io.github.roberbarras.racha.ui.resources.weekday_initials
import io.github.roberbarras.racha.ui.resources.weeks_title
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * Vertical bars drawn on a Canvas (no chart library: H16). [labels] under every [labelEvery]-th bar; the largest value
 * is written over the chart, and the whole chart has one accessible description.
 */
@Composable
internal fun BarChart(values: List<Long>, labels: List<String>, description: String, labelEvery: Int = 1, height: Dp = 120.dp, format: (Long) -> String) {
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val accent = Racha.colors.accent
    val track = Racha.colors.progressTrack
    Column(Modifier.fillMaxWidth().semantics { contentDescription = description }, verticalArrangement = Arrangement.spacedBy(Racha.space.xs)) {
        RText(format(values.maxOrNull() ?: 0L), style = Racha.type.caption, color = Racha.colors.inkSubtle)
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val step = size.width / values.size.coerceAtLeast(1)
            val bar = (step * 0.68f).coerceAtMost(28.dp.toPx())
            drawLine(track, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
            values.forEachIndexed { i, value ->
                val h = size.height * value / max
                if (value > 0) {
                    drawRoundRect(
                        accent, topLeft = Offset(i * step + (step - bar) / 2, size.height - h), size = Size(bar, h.coerceAtLeast(2.dp.toPx())),
                        cornerRadius = CornerRadius(3.dp.toPx()),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, label ->
                RText(if (i % labelEvery == 0) label else "", style = Racha.type.caption, color = Racha.colors.inkMuted,
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ChartCard(title: String, content: @Composable () -> Unit) {
    RCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Racha.space.l), verticalArrangement = Arrangement.spacedBy(Racha.space.m)) {
            SectionHeader(title)
            content()
        }
    }
}

/** The last 12 weeks (Monday to Sunday), this week last. */
@Composable
internal fun WeeksCard(s: PersonalStats) {
    val monday = s.today.plusDays(-(s.today.isoDayOfWeek - 1).toLong())
    val starts = (11 downTo 0).map { monday.plusDays(-7L * it) }
    val byDay = s.days.associate { it.date to it.seconds }
    val values = starts.map { start -> (0..6).sumOf { byDay[start.plusDays(it.toLong())] ?: 0L } }
    val title = stringResource(Res.string.weeks_title)
    ChartCard(title) {
        BarChart(values, starts.map(::dayMonth), title, labelEvery = 2, format = ::compactDuration)
    }
}

private fun dayMonth(date: CalendarDate) = "${date.day}/${date.month}"

@Composable
internal fun HoursCard(s: PersonalStats, modifier: Modifier = Modifier) {
    val title = stringResource(Res.string.by_hour_title)
    Box(modifier) {
        ChartCard(title) { BarChart(s.byHour, (0 until 24).map { "$it" }, title, labelEvery = 6, format = ::compactDuration) }
    }
}

@Composable
internal fun WeekdaysCard(s: PersonalStats, modifier: Modifier = Modifier) {
    val title = stringResource(Res.string.by_weekday_title)
    val initials = stringResource(Res.string.weekday_initials).split(',')
    Box(modifier) {
        ChartCard(title) { BarChart(s.byWeekday, initials, title, format = ::compactDuration) }
    }
}

@Composable
internal fun SessionsCard(s: PersonalStats) {
    val x = s.sessions
    ChartCard(stringResource(Res.string.sessions_title)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Racha.space.m)) {
            Figure(x.count.toString(), stringResource(Res.string.stat_sessions), Modifier.weight(1f))
            Figure(compactDuration(x.averageSeconds), stringResource(Res.string.stat_avg_session), Modifier.weight(1f))
            Figure("${(x.averageSpeed * 100).roundToInt() / 100.0}×", stringResource(Res.string.stat_avg_speed), Modifier.weight(1f))
            Figure("${(x.withSubtitles * 100).roundToInt()} %", stringResource(Res.string.stat_with_subtitles), Modifier.weight(1f))
        }
    }
}

@Composable
private fun Figure(value: String, label: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Racha.space.xxs)) {
        RText(value, style = Racha.type.title)
        RText(label, style = Racha.type.caption, color = Racha.colors.inkMuted, maxLines = 2)
    }
}

/** A bar for a share of the largest value, with its figure on the right. */
@Composable
private fun ShareRow(name: String, detail: String?, value: Long, max: Long, right: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Racha.space.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                RText(name, style = Racha.type.bodyStrong, maxLines = 1)
                detail?.let { RText(it, style = Racha.type.caption, color = Racha.colors.inkMuted, maxLines = 1) }
            }
            RText(right, style = Racha.type.body, color = Racha.colors.inkMuted)
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(Racha.radius.pill)).background(Racha.colors.progressTrack)) {
            Box(Modifier.fillMaxWidth((value.toFloat() / max.coerceAtLeast(1)).coerceIn(0f, 1f)).height(6.dp)
                .clip(RoundedCornerShape(Racha.radius.pill)).background(Racha.colors.accent))
        }
    }
}

@Composable
internal fun DevicesCard(s: PersonalStats) {
    val max = s.devices.maxOf { it.seconds }
    ChartCard(stringResource(Res.string.by_device_title)) {
        s.devices.forEach { d -> ShareRow(d.name, platformName(d.platform), d.seconds, max, compactDuration(d.seconds)) }
    }
}

/** Courses and series with most time: the bar is how far each one is. */
@Composable
internal fun TitlesCard(s: PersonalStats) {
    ChartCard(stringResource(Res.string.titles_title)) {
        s.titles.forEach { t ->
            ShareRow(t.item.displayTitle, "${t.completed}/${t.total}", t.completed.toLong(), t.total.toLong(), compactDuration(t.seconds))
        }
    }
}

@Composable
internal fun RecentCard(s: PersonalStats, dateTime: @Composable (kotlin.time.Instant) -> String) {
    ChartCard(stringResource(Res.string.recent_title)) {
        s.recent.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Racha.space.m)) {
                Column(Modifier.weight(1f)) {
                    RText(r.element?.displayTitle ?: "—", style = Racha.type.bodyStrong, maxLines = 1)
                    RText(r.item?.displayTitle ?: "", style = Racha.type.caption, color = Racha.colors.inkMuted, maxLines = 1)
                }
                RText(dateTime(r.at), style = Racha.type.caption, color = Racha.colors.inkMuted)
            }
        }
    }
}
