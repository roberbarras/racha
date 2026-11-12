package io.github.roberbarras.racha.data

import io.github.roberbarras.racha.application.learning.ContinueWatchingEntry
import io.github.roberbarras.racha.data.Fixtures.courses
import io.github.roberbarras.racha.data.Fixtures.item
import io.github.roberbarras.racha.data.Fixtures.section
import io.github.roberbarras.racha.data.Fixtures.t0
import io.github.roberbarras.racha.data.Fixtures.video
import io.github.roberbarras.racha.data.Fixtures.watched
import io.github.roberbarras.racha.domain.learning.Bookmark
import io.github.roberbarras.racha.domain.learning.Favorite
import io.github.roberbarras.racha.domain.learning.FavoriteTarget
import io.github.roberbarras.racha.domain.learning.HistoryEntry
import io.github.roberbarras.racha.domain.learning.LocalStat
import io.github.roberbarras.racha.domain.library.ContentId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

class LearningPersistenceTest {
    private val db = Fixtures.newDatabase()
    private val item = item()
    private val intro = section(item, "01 - Introduction", 1)
    private val first = video(item, intro, "001 Course Introduction.mp4", 1)
    private val second = video(item, intro, "002 Creating an AWS Account.mp4", 2)

    private suspend fun seed() {
        db.categories.upsert(courses())
        db.library.upsertItem(item)
        db.library.upsertSection(intro)
        db.library.upsertElement(first)
        db.library.upsertElement(second)
    }

    @Test
    fun progressRoundTripsToTheSecond() = runBlocking {
        seed()
        val progress = watched(first, 754, t0).copy(durationSec = 900)
        db.progress.save(progress)

        assertEquals(progress, db.progress.get(first.id))
        assertEquals(listOf(progress), db.progress.forItem(item.id))
    }

    @Test
    fun progressCanExistBeforeItsElementIsIndexed() = runBlocking {
        val synced = watched(first, 30, t0) // arrives from another device first
        db.progress.save(synced)

        assertEquals(synced, db.progress.get(first.id))
    }

    @Test
    fun continueWatchingUpdatesInRealTime() = runBlocking {
        seed()
        val emissions = Channel<List<ContinueWatchingEntry>>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default) { db.progress.observeContinueWatching(10).collect { emissions.send(it) } }
        suspend fun next() = withTimeout(5_000) { emissions.receive() }

        assertEquals(emptyList(), next())

        db.progress.save(watched(first, 120, t0))
        assertEquals(listOf(first.id to 120L), next().map { it.element.id to it.progress.positionSec })

        db.progress.save(watched(second, 45, t0 + 5.minutes))
        assertEquals(listOf(second.id, first.id), next().map { it.element.id }) // most recent first

        db.progress.save(db.progress.get(second.id)!!.finish(t0 + 10.minutes))
        assertEquals(listOf(first.id), next().map { it.element.id }) // completed elements leave the row

        db.library.upsertElement(first.copy(archived = true))
        assertEquals(emptyList(), next()) // files gone from disk are not offered

        collector.cancel()
    }

    @Test
    fun bookmarksHistoryFavoritesAndStatsRoundTrip() = runBlocking {
        val bookmark = Bookmark("b1", first.id, 95, "IAM policy example", t0, t0)
        db.bookmarks.save(bookmark)
        db.bookmarks.save(bookmark.copy(note = "edited", updatedAt = t0 + 1.minutes))
        assertEquals(listOf(bookmark.copy(note = "edited", updatedAt = t0 + 1.minutes)), db.bookmarks.observe(first.id).first())

        val entry = HistoryEntry("h1", first.id, t0, 754)
        db.history.add(entry)
        assertEquals(listOf(entry), db.history.observeRecent(5).first())
        db.history.clear()
        assertEquals(emptyList(), db.history.observeRecent(5).first())

        val favorite = Favorite(FavoriteTarget.Item(item.id), t0)
        db.favorites.add(favorite)
        db.favorites.add(favorite) // idempotent
        assertEquals(listOf(favorite), db.favorites.observeAll().first())
        db.favorites.remove(FavoriteTarget.Item(item.id))
        assertEquals(emptyList(), db.favorites.observeAll().first())

        val stat = LocalStat("watchSeconds", "2026-11-12", 1800.0, t0)
        db.stats.save(stat)
        db.stats.save(stat.copy(value = 2400.0))
        assertEquals(2400.0, db.stats.get("watchSeconds", "2026-11-12")?.value)
        assertNull(db.stats.get("watchSeconds", "2026-11-11"))
        assertEquals(1, db.stats.forMetric("watchSeconds").size)
        assertEquals(ContentId(first.id.value), db.bookmarks.observe(first.id).first().single().elementId)
    }

}
