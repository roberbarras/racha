package io.github.roberbarras.racha.application.library.search

import io.github.roberbarras.racha.application.library.CategoryRepository
import io.github.roberbarras.racha.application.library.LibraryRepository
import io.github.roberbarras.racha.application.resource.ResourceFiles
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.library.ElementKind
import io.github.roberbarras.racha.domain.library.ResourceType
import io.github.roberbarras.racha.domain.library.SearchText
import kotlin.time.Instant

/** A resource whose text contains the query: its clean [text] and the folded copy the match was found in. */
data class TextHit(val elementId: ContentId, val text: String, val folded: String)

/** Outbound port: the text of HTML, TXT and link resources, for search in their content. */
interface ResourceTextIndex {
    /** When the file was indexed (its modification time then); null if it never was. */
    suspend fun indexedVersion(element: ContentId): Instant?

    suspend fun put(element: ContentId, text: String, folded: String, fileModifiedAt: Instant)

    /** Resources whose folded text contains [foldedQuery]. */
    suspend fun search(foldedQuery: String, limit: Int): List<TextHit>
}

/**
 * Keeps the content index current after each scan (spec §2.2 "contenido textual de recursos": it is viable): only
 * new or changed files are read, at most [MAX_BYTES] each. PDFs are not indexed (no text extraction on Android).
 */
class IndexResourceText(
    private val categories: CategoryRepository,
    private val library: LibraryRepository,
    private val files: ResourceFiles,
    private val index: ResourceTextIndex,
) {
    /** Number of files (re)indexed. */
    suspend operator fun invoke(): Int {
        var indexed = 0
        for (category in categories.all()) {
            for (root in categories.roots(category.key)) {
                for (item in library.itemsInRoot(category.key, root.key)) {
                    for (element in library.elements(item.id)) {
                        val type = element.resourceType
                        if (element.kind != ElementKind.RESOURCE || element.archived || type !in INDEXED) continue
                        if (index.indexedVersion(element.id) == element.fileModifiedAt) continue
                        val file = files.readText(root.location, element.relativePath, MAX_BYTES) ?: continue
                        val text = (if (type == ResourceType.HTML) HtmlText.extract(file.text) else HtmlText.collapse(file.text)).take(MAX_CHARS)
                        index.put(element.id, text, SearchText.fold(text), element.fileModifiedAt)
                        indexed++
                    }
                }
            }
        }
        return indexed
    }

    private companion object {
        val INDEXED = setOf(ResourceType.HTML, ResourceType.TEXT, ResourceType.URL)
        const val MAX_BYTES = 1 shl 20
        const val MAX_CHARS = 200_000
    }
}

/** Readable text of an HTML page: no scripts, styles, comments or tags; entities decoded; spaces collapsed. */
object HtmlText {
    private val HIDDEN = Regex("(?is)<(script|style|noscript|template|svg)\\b.*?</\\1\\s*>|<!--.*?-->")
    private val BREAKS = Regex("(?i)<(br|/p|/div|/li|/h[1-6]|/tr|/td|/th)\\b[^>]*>")
    private val TAGS = Regex("<[^>]+>")
    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")
    private val SPACES = Regex("[ \\t\\u00A0]+")
    private val LINES = Regex("\\s*\\n\\s*")
    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "aacute" to "á", "eacute" to "é",
        "iacute" to "í", "oacute" to "ó", "uacute" to "ú", "ntilde" to "ñ", "Aacute" to "Á", "Eacute" to "É", "Iacute" to "Í",
        "Oacute" to "Ó", "Uacute" to "Ú", "Ntilde" to "Ñ", "uuml" to "ü", "iexcl" to "¡", "iquest" to "¿", "hellip" to "…",
        "mdash" to "—", "ndash" to "–", "laquo" to "«", "raquo" to "»", "copy" to "©",
    )

    fun extract(html: String): String {
        val withoutHidden = HIDDEN.replace(html, " ")
        val withBreaks = BREAKS.replace(withoutHidden, "\n")
        val text = TAGS.replace(withBreaks, " ")
        return collapse(ENTITY.replace(text) { m -> decode(m.groupValues[1]) ?: m.value })
    }

    fun collapse(text: String): String = LINES.replace(SPACES.replace(text, " "), "\n").trim()

    private fun decode(entity: String): String? = when {
        entity.startsWith("#x") || entity.startsWith("#X") -> entity.drop(2).toIntOrNull(16)?.let(::codePoint)
        entity.startsWith("#") -> entity.drop(1).toIntOrNull()?.let(::codePoint)
        else -> NAMED[entity]
    }

    private fun codePoint(cp: Int): String? = when {
        cp in 0..0xFFFF -> cp.toChar().toString()
        cp in 0x10000..0x10FFFF -> (cp - 0x10000).let { charArrayOf((0xD800 + (it shr 10)).toChar(), (0xDC00 + (it and 0x3FF)).toChar()).concatToString() }
        else -> null
    }
}
