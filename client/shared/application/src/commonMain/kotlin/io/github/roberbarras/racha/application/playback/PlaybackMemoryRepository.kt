package io.github.roberbarras.racha.application.playback

import io.github.roberbarras.racha.domain.playback.PlaybackChoice
import kotlin.time.Instant

/** Outbound port: what the user chose while watching, per course or series and as [LAST] (H15). This device only. */
interface PlaybackMemoryRepository {
    suspend fun get(key: String): PlaybackChoice?

    suspend fun save(key: String, choice: PlaybackChoice, at: Instant)

    /** Every choice by key (full backup, H16). */
    suspend fun all(): Map<String, PlaybackChoice>

    companion object {
        /** The last choice anywhere: what a course or series without its own starts with. */
        const val LAST = "*"

        /** Remembers nothing (tests, previews). */
        val NONE = object : PlaybackMemoryRepository {
            override suspend fun get(key: String): PlaybackChoice? = null

            override suspend fun save(key: String, choice: PlaybackChoice, at: Instant) = Unit

            override suspend fun all(): Map<String, PlaybackChoice> = emptyMap()
        }
    }
}
