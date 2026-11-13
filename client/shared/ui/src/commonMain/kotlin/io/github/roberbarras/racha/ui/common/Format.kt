package io.github.roberbarras.racha.ui.common

import androidx.compose.runtime.Composable
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.ElementKind
import io.github.roberbarras.racha.domain.library.PlayableElement
import io.github.roberbarras.racha.domain.library.ResourceType
import io.github.roberbarras.racha.domain.library.scan.CalendarDate
import io.github.roberbarras.racha.domain.library.Category
import io.github.roberbarras.racha.ui.design.RachaIcons
import io.github.roberbarras.racha.ui.resources.Res
import io.github.roberbarras.racha.ui.resources.category_courses
import io.github.roberbarras.racha.ui.resources.category_live
import io.github.roberbarras.racha.ui.resources.category_series
import io.github.roberbarras.racha.ui.resources.date_long
import io.github.roberbarras.racha.ui.resources.detected_days_ago
import io.github.roberbarras.racha.ui.resources.detected_hours_ago
import io.github.roberbarras.racha.ui.resources.detected_minutes_ago
import io.github.roberbarras.racha.ui.resources.months
import io.github.roberbarras.racha.ui.resources.weekdays
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** `12:34` or `1:02:15`, as players show it. */
fun timecode(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    fun two(n: Long) = n.toString().padStart(2, '0')
    return if (h > 0) "$h:${two(m)}:${two(sec)}" else "${two(m)}:${two(sec)}"
}

/** `4 h 20 min` style total for course durations. */
fun compactDuration(seconds: Long): String {
    val d: Duration = seconds.seconds
    val h = d.inWholeHours
    val m = d.inWholeMinutes % 60
    return when {
        h > 0 && m > 0 -> "$h h $m min"
        h > 0 -> "$h h"
        else -> "${m.coerceAtLeast(if (seconds > 0) 1 else 0)} min"
    }
}

fun fileSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "${(bytes / (1L shl 30).toDouble() * 10).toLong() / 10.0} GB"
    bytes >= 1L shl 20 -> "${bytes shr 20} MB"
    else -> "${(bytes shr 10).coerceAtLeast(1)} KB"
}

/** "Friday, November 13, 2026" / "viernes, 13 de noviembre de 2026". */
@Composable
fun longDate(date: CalendarDate): String {
    val weekday = stringArrayResource(Res.array.weekdays)[Math.floorMod(date.toEpochDay() + 3, 7L).toInt()]
    val month = stringArrayResource(Res.array.months)[date.month - 1]
    return stringResource(Res.string.date_long, weekday.replaceFirstChar { it.titlecase() }, month, date.day, date.year)
}

@Composable
fun detectedAgo(minutes: Long): String = when {
    minutes < 60 -> stringResource(Res.string.detected_minutes_ago, minutes.coerceAtLeast(1).toInt())
    minutes < 60 * 24 -> stringResource(Res.string.detected_hours_ago, (minutes / 60).toInt())
    else -> stringResource(Res.string.detected_days_ago, (minutes / (60 * 24)).toInt())
}

/** Built-in categories show their localized name unless the user renamed them. */
@Composable
fun categoryName(category: Category): String = category.name ?: when (category.key) {
    CategoryKey.COURSES -> stringResource(Res.string.category_courses)
    CategoryKey.SERIES -> stringResource(Res.string.category_series)
    CategoryKey.LIVE -> stringResource(Res.string.category_live)
    else -> category.key.value
}

fun elementIcon(element: PlayableElement) = when {
    element.kind == ElementKind.VIDEO -> RachaIcons.Play
    element.resourceType == ResourceType.HTML -> RachaIcons.Globe
    element.resourceType == ResourceType.URL -> RachaIcons.Link
    else -> RachaIcons.FileText
}

fun categoryIcon(category: Category) = when (category.key) {
    CategoryKey.COURSES -> RachaIcons.GraduationCap
    CategoryKey.SERIES -> RachaIcons.Tv
    CategoryKey.LIVE -> RachaIcons.Radio
    else -> RachaIcons.Layers
}

fun parseColor(hex: String) = androidx.compose.ui.graphics.Color(("FF" + hex.removePrefix("#")).toLong(16))
