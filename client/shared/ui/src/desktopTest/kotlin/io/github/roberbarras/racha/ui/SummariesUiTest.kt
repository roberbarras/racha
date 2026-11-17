package io.github.roberbarras.racha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import io.github.roberbarras.racha.application.ai.ChatClient
import io.github.roberbarras.racha.application.ai.ChatMessage
import io.github.roberbarras.racha.domain.ai.ProviderPresets
import io.github.roberbarras.racha.domain.ai.SummaryProvider
import io.github.roberbarras.racha.domain.preferences.Settings
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.RachaTheme
import io.github.roberbarras.racha.ui.detail.DetailScreen
import io.github.roberbarras.racha.ui.settings.SummariesSection
import io.github.roberbarras.racha.ui.summary.MarkdownParser
import io.github.roberbarras.racha.ui.summary.MdBlock
import io.github.roberbarras.racha.ui.summary.SummaryScreen
import io.github.roberbarras.racha.ui.summary.SummarySubject
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** "Resumen y claves" (H17): from a lesson with subtitles to a summary on screen and in a .md file. */
@OptIn(ExperimentalTestApi::class)
class SummariesUiTest {
    private val originalLocale = Locale.getDefault()
    private val out = java.io.File(System.getProperty("racha.screenshots") ?: "build/screenshots").apply { mkdirs() }

    private fun androidx.compose.ui.test.ComposeUiTest.shot(name: String) = javax.imageio.ImageIO.write(
        onRoot().captureToImage().toAwtImage(), "png", java.io.File(out, "$name.png"),
    )

    @AfterTest
    fun restoreLocale() = Locale.setDefault(originalLocale)

    private val chat = object : ChatClient {
        var prompts = mutableListOf<String>()
        override suspend fun complete(baseUrl: String, apiKey: String?, model: String, messages: List<ChatMessage>, maxTokens: Int): String {
            prompts += messages.last().content
            return "## Summary\nIAM decides **who** can do *what*.\n\n## Key points\n- Users and groups\n- Policies in `JSON`"
        }
        override suspend fun models(baseUrl: String, apiKey: String?) = listOf("llama-3.3-70b-versatile")
    }

    @Test
    fun markdownBlocksAndInlineStyles() {
        val blocks = MarkdownParser.parse("# Title\n\nA **bold**\nline.\n\n- one\n  wrapped\n2. two\n> quote\n---\n```\ncode\n```")
        assertEquals(
            listOf(
                MdBlock.Heading(1, "Title"), MdBlock.Paragraph("A **bold** line."), MdBlock.Item("•", "one wrapped", 0), MdBlock.Item("2.", "two", 0),
                MdBlock.Quote("quote"), MdBlock.Rule, MdBlock.Code("code"),
            ),
            blocks,
        )
        assertEquals("A bold, italic, code and **unclosed", MarkdownParser.inline("A **bold**, *italic*, `code` and **unclosed").text)
        assertEquals("snake_case_name stays", MarkdownParser.inline("snake_case_name stays").text)
    }

    @Test
    fun aLessonWithSubtitlesIsSummarisedAndSavedAsMarkdownAndOneWithoutIsDisabled() = runDesktopComposeUiTest(1440, 1200) {
        Locale.setDefault(Locale.ENGLISH)
        val saved = mutableListOf<Pair<String, String>>()
        val deps = DemoLibrary.dependencies(chat = chat, saved = saved)
        runBlocking { deps.preferences.set(Settings.SummaryProviders, SummaryProvider.encode(listOf(SummaryProvider("groq", "groq", "Groq", "https://groq.test/v1", "llama")))) }
        var subject by mutableStateOf<SummarySubject?>(null)
        setContent {
            val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                RachaTheme(dark = false) {
                    Box(Modifier.fillMaxSize().background(Racha.colors.background)) {
                        val current = subject
                        if (current == null) DetailScreen(deps, DemoLibrary.aws.id, {}, onSummary = { subject = it }) { _, _ -> }
                        else SummaryScreen(deps, current, onBack = { subject = null }, onSettings = {})
                    }
                }
            }
        }
        // Only the IAM section has a lesson with subtitles: one section button.
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("Section summary").fetchSemanticsNodes().size == 1 }

        // A lesson without subtitles: the entry is there, disabled.
        val menus = onAllNodesWithContentDescription("More actions")
        menus[0].performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Needs subtitles (.srt)").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Summary and key points").performClick()
        waitForIdle()
        assertEquals(null, subject, "disabled: nothing opens")
    }

    @Test
    fun theSummaryScreenWritesKeepsAndExports() = runDesktopComposeUiTest(1440, 1200) {
        Locale.setDefault(Locale.ENGLISH)
        val saved = mutableListOf<Pair<String, String>>()
        val deps = DemoLibrary.dependencies(chat = chat, saved = saved)
        runBlocking { deps.preferences.set(Settings.SummaryProviders, SummaryProvider.encode(listOf(SummaryProvider("groq", "groq", "Groq", "https://groq.test/v1", "llama")))) }
        val lesson = DemoLibrary.awsIamLesson
        setContent {
            val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                RachaTheme(dark = false) {
                    Box(Modifier.fillMaxSize().background(Racha.colors.background)) {
                        SummaryScreen(deps, SummarySubject(lesson.id, lesson.libraryItemId, false, lesson.displayTitle), onBack = {}, onSettings = {})
                    }
                }
            }
        }
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("Generate summary").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Generate summary").performClick()
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("Users and groups").fetchSemanticsNodes().isNotEmpty() }
        assertTrue("IAM users, groups and policies." in chat.prompts.single(), "the transcript was sent")
        onNodeWithText("Key points").assertExists()

        onNodeWithText("Save as .md").performClick()
        waitUntil(timeoutMillis = 10_000) { saved.isNotEmpty() }
        val (name, text) = saved.single()
        assertEquals("IAM Introduction Users, Groups, Policies.md", name)
        assertTrue(text.startsWith("# IAM Introduction: Users, Groups, Policies\n\n## Summary"))
        assertTrue(text.contains("Groq · llama"))
        shot("desktop-summary-lesson-en")
    }

    @Test
    fun providersAreAddedFromPresetsInSettings() = runDesktopComposeUiTest(1440, 1600) {
        Locale.setDefault(Locale.ENGLISH)
        val deps = DemoLibrary.dependencies(chat = chat)
        setContent {
            val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                RachaTheme(dark = false) {
                    Box(Modifier.fillMaxSize().background(Racha.colors.background)) { SummariesSection(deps) }
                }
            }
        }
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("Add provider").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Add provider").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(ProviderPresets.byId("gemini")!!.name).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Google Gemini").performClick()
        waitUntil(timeoutMillis = 10_000) {
            runBlocking { SummaryProvider.decode(deps.preferences.get(Settings.SummaryProviders)) }.map { it.id } == listOf("gemini")
        }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("gemini-2.5-flash").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription("Edit").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Get a key").fetchSemanticsNodes().isNotEmpty() }
        shot("desktop-summary-providers-en")
    }
}
