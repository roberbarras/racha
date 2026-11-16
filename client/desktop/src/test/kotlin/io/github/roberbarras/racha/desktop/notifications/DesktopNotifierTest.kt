package io.github.roberbarras.racha.desktop.notifications

import kotlin.test.Test
import kotlin.test.assertEquals

/** Only the D-Bus call is checked: tests never show notifications on the developer's desktop. */
class DesktopNotifierTest {
    @Test
    fun textIsQuotedSoNothingInItIsReadAsAnotherTypeOrBreaksTheCall() {
        assertEquals("\"Say \\\"hi\\\" \\\\ bye\\n2\"", DesktopNotifier.quoted("Say \"hi\" \\ bye\n2"))
        val command = DesktopNotifier.gdbusCommand("The Expanse: 2 new", "[1, 2] {a}")
        assertEquals(listOf("\"Racha\"", "0", "\"video-x-generic\"", "\"The Expanse: 2 new\"", "\"[1, 2] {a}\"", "[]", "{}", "-1"), command.takeLast(8))
    }
}
