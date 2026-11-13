package io.github.roberbarras.racha.application.playback

import io.github.roberbarras.racha.application.browse.UpdateItemProgress
import io.github.roberbarras.racha.application.learning.BookmarkRepository
import io.github.roberbarras.racha.application.learning.HistoryRepository
import io.github.roberbarras.racha.application.learning.LocalStatRepository
import io.github.roberbarras.racha.application.learning.ProgressRepository
import io.github.roberbarras.racha.application.library.CategoryRepository
import io.github.roberbarras.racha.application.library.LibraryRepository
import io.github.roberbarras.racha.application.library.scan.LibrarySource
import io.github.roberbarras.racha.application.library.scan.LocalDates
import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.domain.learning.Bookmark
import io.github.roberbarras.racha.domain.learning.HistoryEntry
import io.github.roberbarras.racha.domain.learning.LocalStat
import io.github.roberbarras.racha.domain.learning.Progress
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.library.ElementKind
import io.github.roberbarras.racha.domain.library.LibraryItem
import io.github.roberbarras.racha.domain.library.PlayableElement
import io.github.roberbarras.racha.domain.library.Subtitle
import io.github.roberbarras.racha.domain.playback.PlaybackRules
import io.github.roberbarras.racha.domain.playback.StartMode
import io.github.roberbarras.racha.domain.preferences.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant
import kotlin.uuid.Uuid

data class QueueEntry(val element: PlayableElement, val progress: Progress?)

/** Everything the player UI renders (spec §4.2). Positions in milliseconds unless stated. */
data class PlaybackState(
    val item: LibraryItem? = null,
    val element: PlayableElement? = null,
    val queue: List<QueueEntry> = emptyList(),
    val engine: EngineSnapshot = EngineSnapshot(),
    /** Seconds to offer in "Resume from mm:ss / Start over"; null when no prompt is shown. */
    val resumeOffer: Long? = null,
    val loopA: Long? = null,
    val loopB: Long? = null,
    val bookmarks: List<Bookmark> = emptyList(),
    val subtitles: List<Subtitle> = emptyList(),
    val speedSteps: List<Double> = Settings.SpeedSteps.default,
    val speed: Double = 1.0,
    val holdingSpeed: Boolean = false,
    /** The next element is a resource (HTML/URL/TXT/PDF): the UI opens it in the resource viewer. */
    val openResource: PlayableElement? = null,
    /** The last element of the course ended. */
    val reachedEnd: Boolean = false,
    val unavailable: Boolean = false,
) {
    val next: PlayableElement? get() = element?.let { e -> PlaybackRules.next(queue.map { it.element }, e.id) }
    val previous: PlayableElement? get() = element?.let { e -> PlaybackRules.previous(queue.map { it.element }, e.id) }
}

/** Platform values the controller needs but cannot know. */
fun interface SystemLanguage {
    /** Two-letter language of the UI, used when the preferred subtitle language is "app language". */
    fun code(): String
}

/**
 * Shared playback logic for every platform (spec §4.2–§4.3), driving a [PlayerEngine]:
 * resume prompt, periodic and on-pause progress saving, completion and autoplay (also at the outro), A-B loop,
 * bookmarks, speed, external subtitles, history and daily watch time. Runs in the caller's [scope] (the player
 * screen's view model); the periodic work only exists while something is playing.
 */
class PlaybackController(
    private val engine: PlayerEngine,
    private val scope: CoroutineScope,
    private val library: LibraryRepository,
    private val categories: CategoryRepository,
    private val source: LibrarySource,
    private val progress: ProgressRepository,
    private val bookmarks: BookmarkRepository,
    private val history: HistoryRepository,
    private val stats: LocalStatRepository,
    private val preferences: Preferences,
    private val updateItemProgress: UpdateItemProgress,
    private val clock: Clock,
    private val localDates: LocalDates,
    private val systemLanguage: SystemLanguage,
) {
    private val mutableState = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    private var sessionStart: Instant? = null
    private var playingSince: Instant? = null
    private var ticker: Job? = null
    private var bookmarksJob: Job? = null
    private var finishing = false

    /** Progress is read-modified-written from several coroutines (pause, ticker, end): one at a time. */
    private val progressLock = Mutex()

    init {
        scope.launch {
            engine.snapshot.collect { snapshot ->
                val before = mutableState.value.engine
                mutableState.update { it.copy(engine = snapshot) }
                if (snapshot.playing != before.playing) onPlayingChanged(snapshot.playing)
                if (snapshot.state == EngineState.ENDED && before.state != EngineState.ENDED) onEnded()
            }
        }
    }

    /** Opens [id] (spec §4.2 "Continuar viendo" overlay when [mode] is ASK). */
    suspend fun open(id: ContentId, mode: StartMode) {
        endSession()
        val element = library.element(id) ?: return
        val item = library.item(element.libraryItemId) ?: return
        val queue = library.elements(item.id)
        val progressById = progress.forItem(item.id).associateBy { it.elementId }
        val subtitles = library.subtitles(element.id)
        val location = categories.roots(item.categoryKey).firstOrNull { it.key == item.rootKey }?.location
        val saved = progressById[element.id]
        val intro = element.skipIntroSec ?: preferences.get(Settings.SkipIntroSeconds)
        val resume = PlaybackRules.resumePosition(saved)
        val ask = mode == StartMode.ASK && resume != null && resume > intro
        val start = PlaybackRules.startPosition(if (ask) StartMode.RESUME else mode, saved, intro)
        val speed = preferences.get(Settings.DefaultSpeed)
        mutableState.update {
            it.copy(
                item = item,
                element = element,
                queue = queue.map { e -> QueueEntry(e, progressById[e.id]) },
                resumeOffer = if (ask) resume else null,
                loopA = null,
                loopB = null,
                subtitles = subtitles,
                speedSteps = preferences.get(Settings.SpeedSteps),
                speed = speed,
                holdingSpeed = false,
                openResource = null,
                reachedEnd = false,
                unavailable = location == null || element.archived,
            )
        }
        finishing = false
        bookmarksJob?.cancel()
        bookmarksJob = scope.launch { bookmarks.observe(element.id).collect { list -> mutableState.update { it.copy(bookmarks = list) } } }
        if (location == null || element.archived || element.kind != ElementKind.VIDEO) return
        val media = MediaSource(
            uri = source.uri(location, element.relativePath),
            subtitles = subtitles.map { s -> SubtitleSource(s.id.value, source.uri(location, s.relativePath), s.language.value, s.language.value) },
        )
        engine.load(media, start * 1000, playWhenReady = !ask)
        engine.setSpeed(speed.toFloat())
        engine.selectSubtitle(defaultSubtitle(subtitles)?.id?.value)
        sessionStart = clock.now()
    }

    /** Subtitles are off by default (spec §4.2); when enabled, the preferred language, else the app language. */
    private suspend fun defaultSubtitle(subtitles: List<Subtitle>): Subtitle? {
        if (!preferences.get(Settings.SubtitlesEnabled) || subtitles.isEmpty()) return null
        val preferred = preferences.get(Settings.SubtitleLanguage).ifEmpty { systemLanguage.code() }
        return subtitles.firstOrNull { it.language.value == preferred }
            ?: subtitles.firstOrNull { it.language.value.substringBefore('-') == preferred.substringBefore('-') }
    }

    /** Answer to the resume prompt. */
    suspend fun answerResume(resume: Boolean) {
        val offer = mutableState.value.resumeOffer ?: return
        val element = mutableState.value.element ?: return
        mutableState.update { it.copy(resumeOffer = null) }
        val intro = element.skipIntroSec ?: preferences.get(Settings.SkipIntroSeconds)
        engine.seekTo(if (resume) offer * 1000 else intro * 1000L)
        engine.play()
    }

    fun togglePlay() = if (mutableState.value.engine.playing) pause() else engine.play()

    fun play() = engine.play()

    fun pause() {
        engine.pause()
        scope.launch { save() }
    }

    fun seekBy(seconds: Int) {
        val duration = mutableState.value.engine.durationMs
        val target = (engine.positionMs() + seconds * 1000L).coerceAtLeast(0).let { if (duration != null) it.coerceAtMost(duration) else it }
        engine.seekTo(target)
    }

    fun seekTo(positionMs: Long) = engine.seekTo(positionMs.coerceAtLeast(0))

    fun setSpeed(speed: Double) {
        mutableState.update { it.copy(speed = speed) }
        if (!mutableState.value.holdingSpeed) engine.setSpeed(speed.toFloat())
    }

    /** Long-press: temporary speed (2x by default) while held (spec §4.2). */
    fun holdSpeed(held: Boolean) {
        if (held == mutableState.value.holdingSpeed) return
        mutableState.update { it.copy(holdingSpeed = held) }
        scope.launch {
            engine.setSpeed(if (held) preferences.get(Settings.LongPressSpeed).toFloat() else mutableState.value.speed.toFloat())
        }
    }

    fun selectSubtitle(subtitle: Subtitle?) = engine.selectSubtitle(subtitle?.id?.value)

    fun selectAudio(id: String?) = engine.selectAudio(id)

    fun markLoopA() = mutableState.update { it.copy(loopA = engine.positionMs(), loopB = null) }

    fun markLoopB() {
        val a = mutableState.value.loopA ?: return
        val b = engine.positionMs()
        if (b > a) {
            mutableState.update { it.copy(loopB = b) }
            ensureTicker()
        }
    }

    fun clearLoop() = mutableState.update { it.copy(loopA = null, loopB = null) }

    suspend fun addBookmark(note: String) {
        val element = mutableState.value.element ?: return
        val now = clock.now()
        bookmarks.save(Bookmark(Uuid.random().toString(), element.id, engine.positionMs() / 1000, note.trim(), now, now))
    }

    suspend fun deleteBookmark(id: String) = bookmarks.delete(id)

    /** Per-element skip intro/outro (spec §4.2), seconds; null falls back to the global settings. */
    suspend fun setSkips(introSec: Int?, outroSec: Int?) {
        val element = mutableState.value.element ?: return
        val updated = element.copy(skipIntroSec = introSec, skipOutroSec = outroSec, updatedAt = clock.now())
        library.upsertElement(updated)
        mutableState.update { it.copy(element = updated) }
    }

    suspend fun next() {
        val next = mutableState.value.next ?: return
        save()
        navigateTo(next, StartMode.RESUME)
    }

    suspend fun previous() {
        val previous = mutableState.value.previous ?: return
        save()
        navigateTo(previous, StartMode.RESUME)
    }

    suspend fun jumpTo(element: ContentId) {
        save()
        library.element(element)?.let { navigateTo(it, StartMode.RESUME) }
    }

    fun consumeOpenResource() = mutableState.update { it.copy(openResource = null) }

    /** App left the foreground (not picture-in-picture): stop playing and keep the position. */
    suspend fun onBackground() {
        engine.pause()
        save()
    }

    /** Player closed: final save, history entry, engine released. */
    suspend fun close() {
        engine.pause()
        endSession()
        ticker?.cancel()
        bookmarksJob?.cancel()
        engine.release()
    }

    private suspend fun navigateTo(element: PlayableElement, mode: StartMode) {
        if (element.kind == ElementKind.VIDEO) {
            open(element.id, mode)
        } else {
            endSession()
            engine.pause()
            mutableState.update { it.copy(openResource = element) }
        }
    }

    private fun onPlayingChanged(playing: Boolean) {
        if (playing) {
            playingSince = clock.now()
            ensureTicker()
        } else {
            scope.launch { save() }
        }
    }

    /**
     * One coroutine while playing (or looping): saves progress every N seconds (spec §4.3), checks the outro and the
     * A-B loop. It stops itself when nothing needs it, so a paused player costs nothing.
     */
    private fun ensureTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            val saveEveryMs = preferences.get(Settings.SaveIntervalSeconds) * 1000L
            var sinceSave = 0L
            while (isActive) {
                val s = mutableState.value
                val looping = s.loopA != null && s.loopB != null
                if (!s.engine.playing && !looping) break
                val step = if (looping) LOOP_CHECK_MS else OUTRO_CHECK_MS
                delay(step)
                val position = engine.positionMs()
                PlaybackRules.loopTarget(position, s.loopA, s.loopB)?.let { engine.seekTo(it) }
                val element = s.element ?: continue
                val outroAt = PlaybackRules.outroStart(s.engine.durationMs?.div(1000), element.skipOutroSec ?: preferences.get(Settings.SkipOutroSeconds))
                if (outroAt != null && !looping && position / 1000 >= outroAt && s.engine.playing) {
                    onEnded()
                    break
                }
                sinceSave += step
                if (sinceSave >= saveEveryMs) {
                    sinceSave = 0
                    save()
                }
            }
        }
    }

    /** Saves progress to the second and flushes watch time (spec §4.3). */
    private suspend fun save() {
        val s = mutableState.value
        val element = s.element ?: return
        if (element.kind != ElementKind.VIDEO || s.unavailable || s.resumeOffer != null) return
        progressLock.withLock {
            val now = clock.now()
            flushWatchTime(now)
            val durationSec = s.engine.durationMs?.div(1000) ?: element.media.durationSec
            val current = progress.get(element.id) ?: Progress.start(element.id, durationSec, now)
            if (current.completed && s.engine.state == EngineState.ENDED) return
            val updated = current.recordPosition(engine.positionMs() / 1000, durationSec, now)
            progress.save(updated)
            refreshQueueEntry(updated)
        }
    }

    private suspend fun onEnded() {
        if (finishing) return
        finishing = true
        val s = mutableState.value
        val element = s.element ?: return
        val now = clock.now()
        val finished = progressLock.withLock {
            flushWatchTime(now)
            val durationSec = s.engine.durationMs?.div(1000) ?: element.media.durationSec
            (progress.get(element.id) ?: Progress.start(element.id, durationSec, now)).copy(durationSec = durationSec).finish(now)
                .also { progress.save(it) }
        }
        refreshQueueEntry(finished)
        updateItemProgress(element.libraryItemId)
        endSession(finished.positionSec)
        val next = s.next
        when {
            next != null && preferences.get(Settings.AutoplayNext) -> navigateTo(next, StartMode.RESUME)
            next == null -> {
                engine.pause()
                mutableState.update { it.copy(reachedEnd = true) }
            }
            else -> engine.pause()
        }
    }

    private fun refreshQueueEntry(updated: Progress) = mutableState.update { state ->
        state.copy(queue = state.queue.map { if (it.element.id == updated.elementId) it.copy(progress = updated) else it })
    }

    private suspend fun flushWatchTime(now: Instant) {
        val since = playingSince ?: return
        playingSince = if (mutableState.value.engine.playing) now else null
        val seconds = (now - since).inWholeSeconds
        if (seconds <= 0) return
        val day = localDates.dateOf(now).toString()
        val current = stats.get(WATCH_SECONDS, day)
        stats.save(LocalStat(WATCH_SECONDS, day, (current?.value ?: 0.0) + seconds, now))
    }

    /** One history entry per viewing session longer than a few seconds (spec §2.2 "Historial"). */
    private suspend fun endSession(positionAtEnd: Long? = null) {
        val start = sessionStart ?: return
        sessionStart = null
        val element = mutableState.value.element ?: return
        val now = clock.now()
        flushWatchTime(now)
        if ((now - start).inWholeSeconds < MIN_SESSION_SEC) return
        history.add(HistoryEntry(Uuid.random().toString(), element.id, start, positionAtEnd ?: (engine.positionMs() / 1000)))
    }

    companion object {
        const val WATCH_SECONDS = "watchSeconds"
        private const val LOOP_CHECK_MS = 150L
        private const val OUTRO_CHECK_MS = 1_000L
        private const val MIN_SESSION_SEC = 5L
    }
}
