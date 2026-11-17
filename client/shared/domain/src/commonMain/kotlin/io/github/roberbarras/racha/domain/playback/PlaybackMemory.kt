package io.github.roberbarras.racha.domain.playback

/** Subtitles the user chose: none, or a language (`es`, `pt-BR`). */
sealed interface SubtitleChoice {
    data object Off : SubtitleChoice

    data class Language(val code: String) : SubtitleChoice
}

/**
 * What the user chose while watching (H15): kept per course or series and as "the last one anywhere". Null fields were
 * never chosen. The audio track is remembered by language (tracks differ between files) and by position for tracks
 * without one.
 */
data class PlaybackChoice(
    val speed: Double? = null,
    val audioLanguage: String? = null,
    val audioIndex: Int? = null,
    val subtitles: SubtitleChoice? = null,
) {
    private val hasAudio: Boolean get() = audioLanguage != null || audioIndex != null

    /** These choices, and [other]'s for whatever these never chose. */
    fun orElse(other: PlaybackChoice?): PlaybackChoice = if (other == null) this else PlaybackChoice(
        speed = speed ?: other.speed,
        audioLanguage = if (hasAudio) audioLanguage else other.audioLanguage,
        audioIndex = if (hasAudio) audioIndex else other.audioIndex,
        subtitles = subtitles ?: other.subtitles,
    )
}

/** Which tracks of a video match what the user chose before. */
object TrackChoice {
    /**
     * The audio track to play: the remembered language (exactly, then the same base language: `en` for `en-US`), else
     * the remembered position, else null (the file's default, normally the first).
     */
    fun <T> audio(tracks: List<T>, language: (T) -> String?, choice: PlaybackChoice): T? {
        if (tracks.isEmpty()) return null
        choice.audioLanguage?.let { wanted ->
            tracks.firstOrNull { language(it).equals(wanted, ignoreCase = true) }?.let { return it }
            tracks.firstOrNull { base(language(it)) == base(wanted) }?.let { return it }
        }
        return choice.audioIndex?.let { tracks.getOrNull(it) }
    }

    /**
     * The subtitles to show for a remembered choice: none when the user turned them off or this video has none in
     * that language (the choice is kept for the next ones). Null [choice]: never chosen, the caller's default applies.
     */
    fun <T> subtitle(subtitles: List<T>, language: (T) -> String, choice: SubtitleChoice): T? = when (choice) {
        SubtitleChoice.Off -> null
        is SubtitleChoice.Language -> subtitles.firstOrNull { language(it).equals(choice.code, ignoreCase = true) }
            ?: subtitles.firstOrNull { base(language(it)) == base(choice.code) }
    }

    private fun base(code: String?) = code?.substringBefore('-')?.lowercase()
}
