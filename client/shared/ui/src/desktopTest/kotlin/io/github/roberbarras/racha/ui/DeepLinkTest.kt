package io.github.roberbarras.racha.ui

import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.ui.app.DeepLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.receiveAsFlow

class DeepLinkTest {
    private val id = ContentId("a".repeat(64))

    @Test
    fun ourLinksRoundTripAndAnythingElseIsIgnored() {
        assertEquals(DeepLink.Item(id), DeepLink.parse(DeepLink.item(id)))
        assertEquals(DeepLink.Play(id), DeepLink.parse(DeepLink.play(id) + "/"))
        assertEquals(DeepLink.Account, DeepLink.parse("racha://account"))
        assertEquals(DeepLink.Stats, DeepLink.parse("racha://stats"))
        assertNull(DeepLink.parse("https://example.com/item/" + id.value))
        assertNull(DeepLink.parse("racha://item/not-an-id"))
        assertNull(DeepLink.parse("racha://item/${id.value}/extra"))
        assertNull(DeepLink.parse("racha://delete-everything"))
    }
}

/** Links open the screen they name, whatever is showing. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class DeepLinkNavigationTest {
    private val originalLocale = java.util.Locale.getDefault()

    @kotlin.test.AfterTest
    fun restoreLocale() = java.util.Locale.setDefault(originalLocale)

    @Test
    fun anItemLinkOpensTheCourseAndAStatsLinkTheProgressScreen() = androidx.compose.ui.test.runDesktopComposeUiTest(1440, 900) {
        java.util.Locale.setDefault(java.util.Locale.ENGLISH)
        val links = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.BUFFERED)
        val deps = DemoLibrary.dependencies(links = links.receiveAsFlow())
        setContent {
            val owner = object : androidx.lifecycle.ViewModelStoreOwner { override val viewModelStore = androidx.lifecycle.ViewModelStore() }
            androidx.compose.runtime.CompositionLocalProvider(androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner provides owner) {
                io.github.roberbarras.racha.ui.app.RachaApp(deps)
            }
        }
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("12:34 / 45:10").fetchSemanticsNodes().isNotEmpty() }
        links.trySend(DeepLink.item(DemoLibrary.aws.id))
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("Course Introduction").fetchSemanticsNodes().isNotEmpty() }
        links.trySend("racha://stats")
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("Day streak").fetchSemanticsNodes().isNotEmpty() }
        links.trySend("racha://nonsense")
        waitForIdle()
        onAllNodesWithText("Day streak").fetchSemanticsNodes().isNotEmpty().let { check(it) { "unknown links change nothing" } }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionsProvider.onAllNodesWithText(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text))
}
