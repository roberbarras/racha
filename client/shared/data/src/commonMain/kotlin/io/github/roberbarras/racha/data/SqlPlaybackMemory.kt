package io.github.roberbarras.racha.data

import io.github.roberbarras.racha.application.playback.PlaybackMemoryRepository
import io.github.roberbarras.racha.data.db.RachaDatabase
import io.github.roberbarras.racha.domain.playback.PlaybackChoice
import io.github.roberbarras.racha.domain.playback.SubtitleChoice
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant

/** Playback choices in SQLite (`playback_memory`, v8). */
class SqlPlaybackMemory(private val db: RachaDatabase, private val io: CoroutineContext) : PlaybackMemoryRepository {
    private val q get() = db.playbackMemoryQueries

    override suspend fun get(key: String): PlaybackChoice? = withContext(io) {
        q.selectMemory(key).executeAsOneOrNull()?.let { row ->
            PlaybackChoice(
                speed = row.speed,
                audioLanguage = row.audio_language,
                audioIndex = row.audio_index?.toInt(),
                subtitles = when (val s = row.subtitles) {
                    null -> null
                    OFF -> SubtitleChoice.Off
                    else -> SubtitleChoice.Language(s)
                },
            )
        }
    }

    override suspend fun save(key: String, choice: PlaybackChoice, at: Instant) = withContext(io) {
        val subtitles = when (val s = choice.subtitles) {
            null -> null
            SubtitleChoice.Off -> OFF
            is SubtitleChoice.Language -> s.code
        }
        q.upsertMemory(key, choice.speed, choice.audioLanguage, choice.audioIndex?.toLong(), subtitles, at.toEpochMilliseconds())
        Unit
    }

    private companion object {
        const val OFF = "off"
    }
}
