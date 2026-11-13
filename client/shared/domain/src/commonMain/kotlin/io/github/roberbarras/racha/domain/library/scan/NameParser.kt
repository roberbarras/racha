package io.github.roberbarras.racha.domain.library.scan

import io.github.roberbarras.racha.domain.library.LanguageCode

/** Naming conventions of spec §3.2.1, applied to a single file or folder name (never to a whole path). */
object NameParser {

    data class Numbered(val number: Int, val title: String)

    data class Episode(val season: Int?, val episode: Int, val title: String)

    private val SECTION_PREFIX = Regex("""^(\d{1,3})\s*(?:[-–—._)]\s*|\s+)(.+)$""")
    private val ELEMENT_PREFIX = Regex("""^(\d{1,4})(?:\s*[-–—._)]\s*|\s+)(.+)$""")
    private val SEASON = Regex("""^(?:season|temporada|series|serie)\s*[-_. ]?\s*(\d{1,3})\b.*$|^s(\d{1,2})$""", RegexOption.IGNORE_CASE)
    private val SEASON_EPISODE = Regex("""(?:^|[^a-z0-9])s(\d{1,2})\s*e(\d{1,3})(?:[^0-9]|$)""", RegexOption.IGNORE_CASE)
    private val CHAPTER = Regex("""(?:^|[^a-z])cap[ií]tulo\s*[-_. ]?\s*(\d{1,4})(?:[^0-9]|$)""", RegexOption.IGNORE_CASE)
    private val ISO_DATE = Regex("""(?<!\d)(\d{4})-(\d{2})-(\d{2})(?!\d)""")
    private val COMPACT_DATE = Regex("""(?<!\d)(\d{4})(\d{2})(\d{2})(?!\d)""")
    private val EUROPEAN_DATE = Regex("""(?<!\d)(\d{2})-(\d{2})-(\d{4})(?!\d)""")
    private val SUBTITLE_LANGUAGE = Regex("""^(.+)_([A-Za-z]{2,3}(?:[-_][A-Za-z0-9]{2,8})?)$""")
    private val SEPARATORS = Regex("""^[\s\-–—._:)]+|[\s\-–—._:(]+$""")

    /** `01 - Introduction` → (1, "Introduction"). */
    fun sectionPrefix(name: String): Numbered? = numbered(SECTION_PREFIX, name)

    /** `001 Course Introduction` (extension already removed) → (1, "Course Introduction"). */
    fun elementPrefix(stem: String): Numbered? = numbered(ELEMENT_PREFIX, stem)

    /** `Season 2`, `Temporada 3`, `S01` → season number. */
    fun season(name: String): Int? = SEASON.matchEntire(name.trim())?.let { m ->
        (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull()
    }

    /** `S01E02 Pilot`, `Show.s1e2`, `Capítulo 7` → episode (season unknown for "Capítulo"). */
    fun episode(stem: String): Episode? {
        SEASON_EPISODE.find(stem)?.let { m ->
            val rest = (stem.substring(0, m.range.first) + " " + stem.substring(m.range.last + 1)).let(::cleanTitle)
            return Episode(m.groupValues[1].toInt(), m.groupValues[2].toInt(), rest)
        }
        CHAPTER.find(stem)?.let { m ->
            return Episode(null, m.groupValues[1].toInt(), cleanTitle(stem))
        }
        return null
    }

    /** First valid date in the name: `YYYY-MM-DD`, `YYYYMMDD` or `DD-MM-YYYY` (spec §3.2.1). */
    fun date(name: String): CalendarDate? {
        ISO_DATE.findAll(name).forEach { m -> date(m.groupValues[1], m.groupValues[2], m.groupValues[3])?.let { return it } }
        COMPACT_DATE.findAll(name).forEach { m -> date(m.groupValues[1], m.groupValues[2], m.groupValues[3])?.let { return it } }
        EUROPEAN_DATE.findAll(name).forEach { m -> date(m.groupValues[3], m.groupValues[2], m.groupValues[1])?.let { return it } }
        return null
    }

    /** `001 Intro_es` (stem of `001 Intro_es.srt`) → ("001 Intro", es); `001 Intro` → ("001 Intro", null). */
    fun subtitle(stem: String): Pair<String, LanguageCode?> {
        val match = SUBTITLE_LANGUAGE.matchEntire(stem) ?: return stem to null
        val language = runCatching { LanguageCode.parse(match.groupValues[2]) }.getOrNull() ?: return stem to null
        return match.groupValues[1] to language
    }

    /** Readable title from a stem: date and numbering kept out, separators trimmed. */
    fun cleanTitle(text: String): String = text.replace('_', ' ').replace(Regex("\\s+"), " ").replace(SEPARATORS, "").trim()

    private fun numbered(regex: Regex, name: String): Numbered? {
        val match = regex.matchEntire(name.trim()) ?: return null
        val title = cleanTitle(match.groupValues[2])
        if (title.isEmpty()) return null
        return Numbered(match.groupValues[1].toInt(), title)
    }

    private fun date(year: String, month: String, day: String): CalendarDate? =
        CalendarDate.orNull(year.toInt(), month.toInt(), day.toInt())
}
