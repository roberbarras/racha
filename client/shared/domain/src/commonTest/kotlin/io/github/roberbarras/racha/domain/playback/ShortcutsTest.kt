package io.github.roberbarras.racha.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShortcutsTest {
    private fun k(text: String) = KeyCombo.parse(text)!!

    @Test
    fun combosReadAndWriteAsPeopleWriteThem() {
        assertEquals(KeyCombo("Right", ctrl = true, shift = true), KeyCombo.parse("ctrl + shift + Right"))
        assertEquals("Ctrl+Shift+Right", k("Shift+Ctrl+Right").toString())
        assertNull(KeyCombo.parse("Hyper+K"))
        assertNull(KeyCombo.parse("Ctrl+Banana"))
        assertNull(KeyCombo.parse(""))
    }

    @Test
    fun theDefaultsAreTheKeysThePlayerAlwaysHad() {
        val s = Shortcuts.DEFAULT
        assertEquals(PlayerAction.PLAY_PAUSE, s.actionFor(k("Space")))
        assertEquals(PlayerAction.SEEK_BACK, s.actionFor(k("J")))
        assertEquals(PlayerAction.FULLSCREEN, s.actionFor(k("F")))
        assertNull(s.actionFor(k("Ctrl+F")), "modifiers make another combo")
        assertEquals("", s.encode(), "nothing changed, nothing stored")
    }

    @Test
    fun aKeyBelongsToOneActionOnly() {
        val s = Shortcuts.DEFAULT.adding(PlayerAction.NEXT, k("K"))
        assertEquals(PlayerAction.NEXT, s.actionFor(k("K")))
        assertEquals(listOf(k("Space")), s.keysOf(PlayerAction.PLAY_PAUSE), "taken from play/pause")
        assertEquals(s, Shortcuts.decode(s.encode()), "stored and read back")
        val restored = s.reset(PlayerAction.PLAY_PAUSE)
        assertEquals(listOf(k("Space"), k("K")), restored.keysOf(PlayerAction.PLAY_PAUSE))
        assertEquals(listOf(k("N")), restored.keysOf(PlayerAction.NEXT), "and given back by resetting")
    }

    @Test
    fun anActionCanHaveNoKeyAndGarbageIsIgnored() {
        val s = Shortcuts.DEFAULT.with(PlayerAction.MUTE, emptyList())
        assertNull(s.actionFor(k("M")))
        assertEquals("MUTE=", s.encode())
        assertEquals(s, Shortcuts.decode("MUTE=;NOPE=X;SEEK_BACK"))
    }
}
