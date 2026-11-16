package io.github.roberbarras.racha.ui

import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.ui.app.DeepLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
