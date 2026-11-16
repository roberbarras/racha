package io.github.roberbarras.racha.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Menu entry and racha:// handler, in a temporary data folder: the real ~/.local/share is never touched. */
class DesktopIntegrationTest {
    private val data = Files.createTempDirectory("racha-xdg")
    private val commands = mutableListOf<List<String>>()
    private val integration = DesktopIntegration(data) { commands += it; true }

    @Test
    fun installsTheEntryIconAndSchemeHandlerAndRemovesThem() {
        val icon = Files.createTempFile("racha", ".png").also { Files.writeString(it, "png") }
        val entry = integration.install(Path.of("/home/ana/Apps/Racha 1.0.AppImage"), icon)
        val text = Files.readString(entry)
        assertTrue("Exec=\"/home/ana/Apps/Racha 1.0.AppImage\" %u" in text, text)
        assertTrue("MimeType=x-scheme-handler/racha;" in text)
        assertTrue(Files.exists(data.resolve("icons/hicolor/256x256/apps/io.github.roberbarras.racha.png")))
        assertEquals(listOf("xdg-mime", "default", "io.github.roberbarras.racha.desktop", "x-scheme-handler/racha"), commands.last())

        integration.uninstall()
        assertFalse(Files.exists(entry))
    }
}
