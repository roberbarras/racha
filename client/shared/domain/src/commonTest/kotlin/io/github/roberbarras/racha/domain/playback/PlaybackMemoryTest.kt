package io.github.roberbarras.racha.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackMemoryTest {
    private data class Track(val id: String, val language: String?)

    private val tracks = listOf(Track("a1", "spa"), Track("a2", "en-US"), Track("a3", null))

    @Test
    fun aCourseOwnChoiceWinsAndTheLastOneAnywhereFillsTheRest() {
        val course = PlaybackChoice(speed = 1.5)
        val last = PlaybackChoice(speed = 2.0, audioLanguage = "en", subtitles = SubtitleChoice.Language("es"))
        assertEquals(PlaybackChoice(1.5, "en", null, SubtitleChoice.Language("es")), course.orElse(last))
        // Subtitles turned off in this course stay off, whatever was used elsewhere.
        assertEquals(SubtitleChoice.Off, PlaybackChoice(subtitles = SubtitleChoice.Off).orElse(last).subtitles)
        assertEquals(course, course.orElse(null))
    }

    @Test
    fun audioIsFoundByLanguageThenByPosition() {
        assertEquals("a2", TrackChoice.audio(tracks, Track::language, PlaybackChoice(audioLanguage = "en-US"))?.id)
        assertEquals("a2", TrackChoice.audio(tracks, Track::language, PlaybackChoice(audioLanguage = "en-GB"))?.id, "same base language")
        assertEquals("a3", TrackChoice.audio(tracks, Track::language, PlaybackChoice(audioIndex = 2))?.id, "a track without language")
        assertEquals("a1", TrackChoice.audio(tracks, Track::language, PlaybackChoice(audioLanguage = "fr", audioIndex = 0))?.id)
        assertNull(TrackChoice.audio(tracks, Track::language, PlaybackChoice()), "never chosen: the file's default")
        assertNull(TrackChoice.audio(emptyList(), Track::language, PlaybackChoice(audioLanguage = "en")))
    }

    @Test
    fun subtitlesFollowTheChosenLanguageOrStayOff() {
        val subs = listOf("en", "es", "pt-BR")
        assertEquals("es", TrackChoice.subtitle(subs, { it }, SubtitleChoice.Language("es")))
        assertEquals("pt-BR", TrackChoice.subtitle(subs, { it }, SubtitleChoice.Language("pt")))
        assertNull(TrackChoice.subtitle(subs, { it }, SubtitleChoice.Off))
        assertNull(TrackChoice.subtitle(subs, { it }, SubtitleChoice.Language("fr")), "not in this video: none, the choice is kept")
    }
}
