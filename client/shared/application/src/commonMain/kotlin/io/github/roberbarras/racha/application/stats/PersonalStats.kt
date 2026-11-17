package io.github.roberbarras.racha.application.stats

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
)

/** What the account's server knows about watch time on every device (`GET /stats/me`). */
data class RemoteStats(val watchSeconds: Long, val byDay: Map<CalendarDate, Long>, val byCategory: Map<String, Long>)

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

        return PersonalStats(
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
    }
}

/** Outbound port: `GET /stats/me` (§7.3) with days counted in the IANA [zone]. */
interface StatsServer {
    suspend fun personalStats(address: ServerAddress, accessToken: String, zone: String): RemoteStats
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
            try {
                server.personalStats(address, token, zone())
            } catch (e: RemoteError.Unauthorized) {
                // The server restarted with another access-token key: the session is still good, renew the token once.
                server.personalStats(address, account.accessToken(forceRefresh = true) ?: return null, zone())
            }
        } catch (e: RemoteError) {
            null
        }
    }
}
