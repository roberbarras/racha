package io.github.roberbarras.racha.data

import io.github.roberbarras.racha.application.playback.PlaybackMemoryRepository
import io.github.roberbarras.racha.data.db.Playback_memory
import io.github.roberbarras.racha.data.db.RachaDatabase
import io.github.roberbarras.racha.domain.playback.PlaybackChoice
import io.github.roberbarras.racha.domain.playback.SubtitleChoice
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant

/** Playback choices in SQLite (`playback_memory`, v8). */
class SqlPlaybackMemory(private val db: RachaDatabase, private val io: CoroutineContext) : PlaybackMemoryRepository {
    private val q get() = db.playbackMemoryQueries

    override suspend fun get(key: String): PlaybackChoice? = withContext(io) { q.selectMemory(key).executeAsOneOrNull()?.toChoice() }

    override suspend fun all(): Map<String, PlaybackChoice> = withContext(io) {
        q.selectAllMemory().executeAsList().associate { it.item_key to it.toChoice() }
    }

    private fun Playback_memory.toChoice() = PlaybackChoice(
        speed = speed,
        audioLanguage = audio_language,
        audioIndex = audio_index?.toInt(),
        subtitles = when (val s = subtitles) {
            null -> null
            OFF -> SubtitleChoice.Off
            else -> SubtitleChoice.Language(s)
        },
    )

    override suspend fun save(key: String, choice: PlaybackChoice, at: Instant) = withContext(io) {
        val subtitles = when (val s = choice.subtitles) {
            null -> null
            SubtitleChoice.Off -> OFF
            is SubtitleChoice.Language -> s.code
        }
        q.upsertMemory(key, choice.speed, choice.audioLanguage, choice.audioIndex?.toLong(), subtitles, at.toEpochMilliseconds())
        Unit
    }

    /** Forgets every choice (device tests start from a known state). */
    suspend fun clear() = withContext(io) { q.clearMemory(); Unit }

    private companion object {
        const val OFF = "off"
    }
}
