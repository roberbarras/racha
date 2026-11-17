package io.github.roberbarras.racha.application.stats

import kotlinx.coroutines.flow.first
import kotlin.time.Instant
import io.github.roberbarras.racha.domain.library.PlayableElement
import io.github.roberbarras.racha.application.learning.HistoryRepository
import io.github.roberbarras.racha.application.learning.LocalStatRepository
import io.github.roberbarras.racha.application.learning.ProgressRepository
import io.github.roberbarras.racha.application.library.CategoryRepository
import io.github.roberbarras.racha.application.library.LibraryRepository
import io.github.roberbarras.racha.application.library.scan.LocalDates
import io.github.roberbarras.racha.application.playback.PlaybackController
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.domain.learning.LocalStat
import io.github.roberbarras.racha.domain.learning.Streak
import io.github.roberbarras.racha.domain.library.Category
import io.github.roberbarras.racha.domain.library.LibraryItem
import io.github.roberbarras.racha.domain.library.scan.CalendarDate
import kotlin.time.Duration.Companion.days
import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.application.sync.AccountService
import io.github.roberbarras.racha.application.sync.RemoteError
import io.github.roberbarras.racha.application.sync.ServerAddress
import io.github.roberbarras.racha.domain.preferences.Settings

/** One day of the activity map: seconds watched and whether anything was watched or read. */
data class DayActivity(val date: CalendarDate, val seconds: Long, val active: Boolean)

data class CategoryTime(val category: Category, val seconds: Long)

/** Viewing sessions (H16): how many, how long on average, at what speed (weighted by time), share with subtitles. */
data class SessionStats(val count: Long, val averageSeconds: Long, val averageSpeed: Double, val withSubtitles: Double)

/** A course or series watched: time spent and how far it is ([completed] of [total] elements). */
data class TitleTime(val item: LibraryItem, val seconds: Long, val completed: Int, val total: Int)

/** Watch time on one of the account's devices (from the server: this device alone does not know the others). */
data class DeviceTime(val name: String, val platform: String, val seconds: Long)

/** A recent viewing, with what it was. */
data class RecentWatch(val at: Instant, val element: PlayableElement?, val item: LibraryItem?, val completed: Boolean)

/** Personal statistics (spec §2.2): time watched, streaks, activity heat map, completed courses. */
data class PersonalStats(
    val today: CalendarDate,
    val totalSeconds: Long,
    val last7DaysSeconds: Long,
    val streakDays: Int,
    val bestStreakDays: Int,
    val activeDays: Int,
    val completedElements: Long,
    val completedItems: List<LibraryItem>,
    /** The last [LoadPersonalStats.DAYS] days, oldest first. */
    val days: List<DayActivity>,
    val byCategory: List<CategoryTime>,
    /** True when the server's figures (every device of the account) were included. */
    val allDevices: Boolean,
    /** Seconds by hour of the day (24 entries, 0 h first) and by weekday (7, Monday first). H16. */
    val byHour: List<Long> = List(24) { 0L },
    val byWeekday: List<Long> = List(7) { 0L },
    val sessions: SessionStats = SessionStats(0, 0, 1.0, 0.0),
    /** The courses and series with the most time, most first. */
    val titles: List<TitleTime> = emptyList(),
    val devices: List<DeviceTime> = emptyList(),
    val recent: List<RecentWatch> = emptyList(),
)

/** What the account's server knows about watch time on every device (`GET /stats/me`); [details] from H14 servers. */
data class RemoteStats(
    val watchSeconds: Long,
    val byDay: Map<CalendarDate, Long>,
    val byCategory: Map<String, Long>,
    val details: RemoteDetails? = null,
)

/** `GET /stats/me/details`: breakdowns over every device of the account (H16). */
data class RemoteDetails(
    val sessions: SessionStats,
    val byHour: Map<Int, Long>,
    val byWeekday: Map<Int, Long>,
    val devices: List<DeviceTime>,
    /** Seconds watched per item id. */
    val titleSeconds: Map<String, Long>,
)

/**
 * Builds [PersonalStats] from the local database, so it works offline. Watch time is counted per device; when signed
 * in, the server's figures for every device are merged in (per day and category, the larger of the two: the server
 * may not have this device's latest minutes yet). Streaks and completions come from synced data.
 */
class LoadPersonalStats(
    private val categories: CategoryRepository,
    private val library: LibraryRepository,
    private val progress: ProgressRepository,
    private val history: HistoryRepository,
    private val stats: LocalStatRepository,
    private val localDates: LocalDates,
    private val clock: Clock,
    private val remote: suspend () -> RemoteStats? = { null },
) {
    /** [includeRemote] false: this device only, without waiting for the network. */
    suspend operator fun invoke(includeRemote: Boolean = true): PersonalStats {
        val now = clock.now()
        val today = localDates.dateOf(now)
        val server = if (includeRemote) runCatching { remote() }.getOrNull() else null

        val localDays = stats.forMetric(PlaybackController.WATCH_SECONDS).perDay()
        val perDay = merge(localDays, server?.byDay.orEmpty())
        // Active days come from the (synced) history, like the streak on Home: every viewing session leaves an entry.
        val active = history.watchTimesSince(now - HISTORY_DAYS.days).map(localDates::dateOf).toSet()

        val all = categories.all()
        val byCategory = all.map { category ->
            val local = stats.forMetric(PlaybackController.categoryMetric(category.key.value)).sumOf { it.value.toLong() }
            CategoryTime(category, maxOf(local, server?.byCategory?.get(category.key.value) ?: 0))
        }.filter { it.seconds > 0 }.sortedByDescending { it.seconds }

        val first = today.plusDays(-(DAYS - 1).toLong())
        val days = (0 until DAYS).map { offset ->
            val date = first.plusDays(offset.toLong())
            DayActivity(date, perDay[date] ?: 0, date in active)
        }
        val completedItems = all.flatMap { library.items(it.key) }
            .filter { !it.archived && it.totalItems > 0 && it.progressPct >= COMPLETE_PCT }
            .sortedBy { it.displayTitle.lowercase() }

        val details = server?.details
        val byHour = (0 until 24).map { hour -> maxOf(allTime(PlaybackController.hourMetric(hour)), details?.byHour?.get(hour) ?: 0) }
        val byWeekday = (1..7).map { day -> maxOf(allTime(PlaybackController.weekdayMetric(day)), details?.byWeekday?.get(day) ?: 0) }
        val localSessions = allTime(PlaybackController.SESSIONS)
        val localSessionSeconds = allTime(PlaybackController.SESSION_SECONDS)
        val sessions = details?.sessions?.takeIf { it.count >= localSessions } ?: SessionStats(
            count = localSessions,
            averageSeconds = if (localSessions > 0) localSessionSeconds / localSessions else 0,
            averageSpeed = if (localSessionSeconds > 0) allTime(PlaybackController.SESSION_SPEED_SECONDS).toDouble() / localSessionSeconds else 1.0,
            withSubtitles = if (localSessions > 0) allTime(PlaybackController.SESSIONS_WITH_SUBTITLES).toDouble() / localSessions else 0.0,
        )
        val titles = all.flatMap { library.items(it.key) }.filter { !it.archived }
            .map { item -> item to maxOf(allTime(PlaybackController.itemMetric(item.id.value)), details?.titleSeconds?.get(item.id.value) ?: 0) }
            .filter { (item, seconds) -> seconds > 0 || item.progressPct > 0 }
            .sortedWith(compareByDescending<Pair<LibraryItem, Long>> { it.second }.thenByDescending { it.first.progressPct })
            .take(TITLES)
            .map { (item, seconds) -> TitleTime(item, seconds, progress.forItem(item.id).count { it.completed }, item.totalItems) }
        val recent = history.observeRecent(RECENT).first().map { entry ->
            val element = library.element(entry.elementId)
            RecentWatch(entry.watchedAt, element, element?.let { library.item(it.libraryItemId) }, progress.get(entry.elementId)?.completed == true)
        }

        return PersonalStats(
            byHour = byHour,
            byWeekday = byWeekday,
            sessions = sessions,
            titles = titles,
            devices = details?.devices.orEmpty(),
            recent = recent,
            today = today,
            totalSeconds = maxOf(localDays.values.sum(), server?.watchSeconds ?: 0),
            last7DaysSeconds = (0..6).sumOf { perDay[today.plusDays(-it.toLong())] ?: 0 },
            streakDays = Streak.current(active, today),
            bestStreakDays = Streak.longest(active),
            activeDays = active.size,
            completedElements = progress.countCompleted(),
            completedItems = completedItems,
            days = days,
            byCategory = byCategory,
            allDevices = server != null,
        )
    }

    private suspend fun allTime(metric: String): Long = stats.get(metric, PlaybackController.ALL_TIME)?.value?.toLong() ?: 0

    private fun List<LocalStat>.perDay(): Map<CalendarDate, Long> =
        mapNotNull { stat -> CalendarDate.parseOrNull(stat.period)?.let { it to stat.value.toLong() } }.toMap()

    private fun merge(a: Map<CalendarDate, Long>, b: Map<CalendarDate, Long>): Map<CalendarDate, Long> =
        (a.keys + b.keys).associateWith { maxOf(a[it] ?: 0, b[it] ?: 0) }

    companion object {
        /** A year of weeks for the heat map. */
        const val DAYS = 371

        /** History looked at for streaks: long enough for the best one to be meaningful. */
        const val HISTORY_DAYS = 3 * 365

        const val COMPLETE_PCT = 99.5

        const val TITLES = 20
        const val RECENT = 15
    }
}

/** Outbound port: `GET /stats/me` (§7.3) with days counted in the IANA [zone]. */
interface StatsServer {
    suspend fun personalStats(address: ServerAddress, accessToken: String, zone: String): RemoteStats

    /** `GET /stats/me/details` (servers from H14); null where the server does not have it. */
    suspend fun personalDetails(address: ServerAddress, accessToken: String, zone: String): RemoteDetails? = null
}

/** The account's figures when signed in and reachable; null otherwise (the screen then shows this device only). */
class RemoteStatsSource(
    private val server: StatsServer,
    private val account: AccountService,
    private val preferences: Preferences,
    private val zone: () -> String,
) : suspend () -> RemoteStats? {
    override suspend fun invoke(): RemoteStats? {
        if (!preferences.get(Settings.SyncEnabled)) return null
        val address = ServerAddress.from(preferences) ?: return null
        val token = account.accessToken() ?: return null
        return try {
            var current = token
            val stats = try {
                server.personalStats(address, current, zone())
            } catch (e: RemoteError.Unauthorized) {
                // The server restarted with another access-token key: the session is still good, renew the token once.
                current = account.accessToken(forceRefresh = true) ?: return null
                server.personalStats(address, current, zone())
            }
            // The breakdowns are a bonus: an older server without them still gives the totals.
            val details = try {
                server.personalDetails(address, current, zone())
            } catch (e: RemoteError) {
                null
            }
            stats.copy(details = details)
        } catch (e: RemoteError) {
            null
        }
    }
}
