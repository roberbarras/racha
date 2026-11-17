package io.github.roberbarras.racha.ui.summary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.roberbarras.racha.ui.design.RText
import io.github.roberbarras.racha.ui.design.Racha

/** The Markdown summaries use (H17): headings, lists, quotes, code, rules and paragraphs. Nothing is fetched or run. */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    /** [marker] is "•" or "1."… as written. */
    data class Item(val marker: String, val text: String, val depth: Int) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data object Rule : MdBlock
}

object MarkdownParser {
    private val heading = Regex("^(#{1,6})\\s+(.*?)\\s*#*$")
    private val bullet = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val numbered = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")
    private val rule = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")

    fun parse(markdown: String): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        val paragraph = StringBuilder()
        fun flush() {
            if (paragraph.isNotBlank()) blocks += MdBlock.Paragraph(paragraph.toString().trim())
            paragraph.clear()
        }
        val lines = markdown.replace("\r", "").lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.trimStart().startsWith("```") -> {
                    flush()
                    val code = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) code += lines[i++]
                    blocks += MdBlock.Code(code.joinToString("\n"))
                }
                line.isBlank() -> flush()
                rule.matches(line) -> { flush(); blocks += MdBlock.Rule }
                heading.matches(line.trim()) -> {
                    flush()
                    val m = heading.find(line.trim())!!
                    blocks += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2])
                }
                bullet.matches(line) -> {
                    flush()
                    val m = bullet.find(line)!!
                    blocks += MdBlock.Item("•", m.groupValues[2], m.groupValues[1].length / 2)
                }
                numbered.matches(line) -> {
                    flush()
                    val m = numbered.find(line)!!
                    blocks += MdBlock.Item(m.groupValues[2] + ".", m.groupValues[3], m.groupValues[1].length / 2)
                }
                line.trimStart().startsWith(">") -> { flush(); blocks += MdBlock.Quote(line.trimStart().removePrefix(">").trim()) }
                // A wrapped list item continues on the next line.
                blocks.lastOrNull() is MdBlock.Item && paragraph.isEmpty() && line.startsWith("  ") -> {
                    val last = blocks.removeAt(blocks.lastIndex) as MdBlock.Item
                    blocks += last.copy(text = last.text + " " + line.trim())
                }
                else -> paragraph.append(if (paragraph.isEmpty()) "" else " ").append(line.trim())
            }
            i++
        }
        flush()
        return blocks
    }

    /** `**bold**`, `*italic*` / `_italic_` and `` `code` ``; anything unclosed stays as written. */
    fun inline(text: String, code: SpanStyle = SpanStyle(fontFamily = FontFamily.Monospace)): AnnotatedString = buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val bold = text.startsWith("**", i) || text.startsWith("__", i)
            val close = when {
                bold -> text.indexOf(text.substring(i, i + 2), i + 2).takeIf { it > i + 2 }
                c == '`' -> text.indexOf('`', i + 1).takeIf { it > i + 1 }
                (c == '*' || c == '_') && i + 1 < text.length && !text[i + 1].isWhitespace() && (i == 0 || !text[i - 1].isLetterOrDigit()) ->
                    text.indexOf(c, i + 1).takeIf { it > i + 1 && !text[it - 1].isWhitespace() }
                else -> null
            }
            if (close == null) {
                val literal = if (bold) 2 else 1
                append(text, i, i + literal)
                i += literal
                continue
            }
            when {
                bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(inline(text.substring(i + 2, close), code)) }.also { i = close + 2 }
                c == '`' -> withStyle(code) { append(text.substring(i + 1, close)) }.also { i = close + 1 }
                else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inline(text.substring(i + 1, close), code)) }.also { i = close + 1 }
            }
        }
    }
}

/** Renders a summary; selectable, so parts can be copied. */
@Composable
fun MarkdownView(markdown: String, modifier: Modifier = Modifier, color: Color = Racha.colors.ink, muted: Color = Racha.colors.inkMuted) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }
    val codeStyle = SpanStyle(fontFamily = FontFamily.Monospace, background = muted.copy(alpha = 0.15f))
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Racha.space.s)) {
            blocks.forEach { block ->
                when (block) {
                    is MdBlock.Heading -> RText(
                        MarkdownParser.inline(block.text, codeStyle),
                        Modifier.padding(top = if (block.level <= 2) Racha.space.m else Racha.space.s),
                        style = when (block.level) {
                            1 -> Racha.type.title
                            2 -> Racha.type.heading
                            else -> Racha.type.bodyStrong
                        },
                        color = color,
                    )
                    is MdBlock.Paragraph -> RText(MarkdownParser.inline(block.text, codeStyle), style = Racha.type.body, color = color)
                    is MdBlock.Item -> Row(Modifier.padding(start = (12 * block.depth).dp), horizontalArrangement = Arrangement.spacedBy(Racha.space.s)) {
                        RText(block.marker, Modifier.width(if (block.marker == "•") 12.dp else 24.dp), style = Racha.type.body, color = muted)
                        RText(MarkdownParser.inline(block.text, codeStyle), Modifier.weight(1f), style = Racha.type.body, color = color)
                    }
                    is MdBlock.Quote -> Row(horizontalArrangement = Arrangement.spacedBy(Racha.space.m)) {
                        Box(Modifier.width(3.dp).height(20.dp).background(muted.copy(alpha = 0.5f)))
                        RText(MarkdownParser.inline(block.text, codeStyle), style = Racha.type.body, color = muted)
                    }
                    is MdBlock.Code -> RText(
                        block.text,
                        Modifier.fillMaxWidth().background(muted.copy(alpha = 0.12f), RoundedCornerShape(Racha.radius.s)).padding(Racha.space.m),
                        style = Racha.type.caption.copy(fontFamily = FontFamily.Monospace), color = color,
                    )
                    MdBlock.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(muted.copy(alpha = 0.3f)))
                }
            }
        }
    }
}
