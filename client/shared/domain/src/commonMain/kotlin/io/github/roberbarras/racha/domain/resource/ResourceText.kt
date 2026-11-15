package io.github.roberbarras.racha.domain.resource

/** Links the viewer may hand to the system browser; anything else (javascript:, file:, data:…) is shown as text. */
object ExternalLinks {
    private val allowed = setOf("http", "https", "mailto")

    fun isSafe(url: String): Boolean {
        val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
        return scheme in allowed && url.length > scheme.length + 1 && url.none { it.isWhitespace() || it.code < 0x20 }
    }
}

/**
 * `.url` files (spec §5): Windows "Internet Shortcut" INI (`[InternetShortcut]` / `URL=…`). Files that just contain
 * a bare link are accepted too. Only [ExternalLinks.isSafe] targets are returned.
 */
object InternetShortcut {
    fun url(content: String): String? {
        val lines = content.removePrefix("﻿").lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        var inSection = lines.none { it.startsWith("[") }
        for (line in lines) {
            if (line.startsWith("[")) {
                inSection = line.equals("[InternetShortcut]", ignoreCase = true)
                continue
            }
            if (inSection && line.startsWith("URL=", ignoreCase = true)) return line.substring(4).trim().takeIf(ExternalLinks::isSafe)
        }
        return lines.firstOrNull()?.takeIf { lines.size == 1 && ExternalLinks.isSafe(it) }
    }
}

/** A piece of plain text, or a link inside it (`.txt` resources are shown as text with clickable links). */
sealed interface TextSegment {
    val text: String

    data class Plain(override val text: String) : TextSegment

    data class Link(override val text: String, val url: String) : TextSegment
}

object TextLinks {
    // Scheme links and bare "www." links; trailing punctuation that usually ends a sentence is left out.
    private val pattern = Regex("""(?i)\b(?:https?://|mailto:|www\.)[^\s<>"'`]+""")
    private const val TRAILING = ".,;:!?)]}»"

    fun split(text: String): List<TextSegment> {
        val segments = mutableListOf<TextSegment>()
        var cursor = 0
        for (match in pattern.findAll(text)) {
            var value = match.value
            while (value.isNotEmpty() && value.last() in TRAILING && !(value.last() == ')' && value.count { it == '(' } >= value.count { it == ')' })) {
                value = value.dropLast(1)
            }
            val url = if (value.startsWith("www.", ignoreCase = true)) "https://$value" else value
            if (!ExternalLinks.isSafe(url)) continue
            if (match.range.first > cursor) segments += TextSegment.Plain(text.substring(cursor, match.range.first))
            segments += TextSegment.Link(value, url)
            cursor = match.range.first + value.length
        }
        if (cursor < text.length) segments += TextSegment.Plain(text.substring(cursor))
        return segments
    }
}
