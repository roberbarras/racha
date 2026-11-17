package io.github.roberbarras.racha.domain.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SummariesTest {
    @Test
    fun subtitlesBecomePlainTextOnce() {
        val srt = "﻿1\r\n00:00:01,000 --> 00:00:03,500\r\n<i>Hola</i> a todos,\r\n\r\n2\n00:00:03,500 --> 00:00:05,000 X1:0\nhoy vemos IAM.\n\n" +
            "3\n00:00:05,000 --> 00:00:06,000\nhoy vemos IAM.\n\n4\n00:00:06,000 --> 00:00:07,000\n{\\an8}Usuarios y grupos\n"
        assertEquals("Hola a todos, hoy vemos IAM. Usuarios y grupos", Transcript.fromSrt(srt))
    }

    @Test
    fun longTextIsCutAtSentenceEnds() {
        val text = (1..40).joinToString(" ") { "Frase número $it." }
        val pieces = Transcript.chunks(text, 120)
        assertTrue(pieces.size > 1)
        assertTrue(pieces.all { it.length <= 120 })
        assertTrue(pieces.dropLast(1).all { it.endsWith(".") }, "cut where a sentence ends")
        assertEquals(text.replace(" ", ""), pieces.joinToString("").replace(" ", ""), "nothing lost")
        assertEquals(listOf("corto"), Transcript.chunks("corto", 120))
    }

    @Test
    fun providersAreStoredInTheUsersOrder() {
        val list = listOf(
            SummaryProvider("a", "groq", "Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
            SummaryProvider("b", "gemini", "Gemini\tpersonal", "https://x", "gemini-2.5-flash", enabled = false),
        )
        val back = SummaryProvider.decode(SummaryProvider.encode(list))
        assertEquals(listOf("a", "b"), back.map { it.id })
        assertEquals("Gemini personal", back[1].name, "a tab in a name cannot break the line")
        assertEquals(false, back[1].enabled)
        assertTrue(SummaryProvider.decode("garbage\n\n").isEmpty())
    }

    @Test
    fun everyPresetButTheCustomOneIsReady() {
        ProviderPresets.all.filter { it.id != "custom" }.forEach { p ->
            assertTrue(p.baseUrl.startsWith("http") && p.model.isNotBlank(), p.id)
        }
        assertTrue(ProviderPresets.all.count { it.freeTier } >= 6)
    }

    @Test
    fun promptsFollowTheAppLanguage() {
        assertTrue("## Claves" in SummaryPrompts.lesson("es", "IAM", "AWS", "texto"))
        assertTrue("## Key points" in SummaryPrompts.lesson("en", "IAM", "AWS", "text"))
        assertTrue("### Clase 1" in SummaryPrompts.combine("es", "Sección", "AWS", listOf("Clase 1" to "…")))
    }
}
