package io.github.roberbarras.racha.domain.library

/**
 * Text as search compares it (spec §2.2 "búsqueda global"): lower case and without accents, so "jose" finds "José"
 * and "NIÑO" finds "nino". One character in, one character out: a match position in the folded text is the same
 * position in the original, which lets results quote the original words.
 */
object SearchText {
    private val FOLD: Map<Char, Char> = buildMap {
        fun map(from: String, to: Char) = from.forEach { put(it, to) }
        map("àáâãäåāăąǎ", 'a'); map("çćĉċč", 'c'); map("ďđ", 'd'); map("èéêëēĕėęěẽ", 'e'); map("ĝğġģ", 'g')
        map("ĥħ", 'h'); map("ìíîïĩīĭįıǐ", 'i'); map("ĵ", 'j'); map("ķ", 'k'); map("ĺļľŀł", 'l'); map("ñńņňŉ", 'n')
        map("òóôõöøōŏőǒ", 'o'); map("ŕŗř", 'r'); map("śŝşšș", 's'); map("ţťŧț", 't'); map("ùúûüũūŭůűųǔ", 'u')
        map("ŵ", 'w'); map("ýÿŷ", 'y'); map("źżž", 'z')
    }

    fun fold(text: String): String = buildString(text.length) {
        for (c in text) {
            val lower = c.lowercaseChar()
            append(FOLD[lower] ?: lower)
        }
    }

    /** Where [query] (already folded) appears in [folded], or -1. */
    fun indexIn(folded: String, query: String): Int = if (query.isEmpty()) -1 else folded.indexOf(query)
}
