package io.github.roberbarras.racha.domain.library.scan

import io.github.roberbarras.racha.domain.library.LanguageCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NameParserTest {
    @Test
    fun sectionAndElementPrefixes() {
        assertEquals(NameParser.Numbered(4, "IAM - Identity and Access Management"), NameParser.sectionPrefix("04 - IAM - Identity and Access Management"))
        assertEquals(NameParser.Numbered(2, "Code & Slides Download"), NameParser.sectionPrefix("02 - Code & Slides Download"))
        assertEquals(NameParser.Numbered(1, "Intro"), NameParser.sectionPrefix("1. Intro"))
        assertEquals(NameParser.Numbered(1, "Course-Material-Download"), NameParser.elementPrefix("001 Course-Material-Download"))
        assertEquals(NameParser.Numbered(21, "Identity and Access Management - Quiz"), NameParser.elementPrefix("021 Identity and Access Management - Quiz"))
        assertNull(NameParser.sectionPrefix("Introduction"))
        assertNull(NameParser.elementPrefix("external-links"))
    }

    @Test
    fun seasonsEpisodesAndChapters() {
        assertEquals(1, NameParser.season("Season 1"))
        assertEquals(3, NameParser.season("Temporada 3"))
        assertEquals(2, NameParser.season("S02"))
        assertNull(NameParser.season("Seasonal recipes"))
        assertEquals(NameParser.Episode(1, 2, "Pilot"), NameParser.episode("S01E02 Pilot"))
        assertEquals(NameParser.Episode(1, 2, "Show"), NameParser.episode("Show.s01e02"))
        assertEquals(7, NameParser.episode("Capítulo 7 - El regreso")?.episode)
        assertNull(NameParser.episode("Sessions 2024"))
    }

    @Test
    fun datesInTheThreeSpecFormats() {
        assertEquals(CalendarDate(2026, 11, 13), NameParser.date("Directo 2026-11-13 Q&A"))
        assertEquals(CalendarDate(2026, 11, 13), NameParser.date("stream_20261113"))
        assertEquals(CalendarDate(2026, 11, 13), NameParser.date("13-11-2026 charla"))
        assertNull(NameParser.date("1920x1080 2026-13-45"))
        assertNull(NameParser.date("12345678"))
    }

    @Test
    fun subtitleLanguageSuffix() {
        assertEquals("001 Intro" to LanguageCode("es"), NameParser.subtitle("001 Intro_es"))
        assertEquals("Movie" to LanguageCode("pt-br"), NameParser.subtitle("Movie_pt-BR"))
        assertEquals("001 Intro" to null, NameParser.subtitle("001 Intro"))
    }

    @Test
    fun naturalOrderComparesNumbersByValue() {
        assertEquals(listOf("Part 1", "Part 2", "Part 10", "part 11"), listOf("Part 10", "part 11", "Part 2", "Part 1").sortedWith(NaturalOrder))
    }
}
