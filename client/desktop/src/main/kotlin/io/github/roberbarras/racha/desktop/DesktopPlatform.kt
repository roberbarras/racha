package io.github.roberbarras.racha.desktop

import io.github.roberbarras.racha.desktop.player.DesktopPlayerHooks
import io.github.roberbarras.racha.desktop.resource.DesktopResourceHooks
import io.github.roberbarras.racha.ui.resource.ResourceHooks
import io.github.roberbarras.racha.files.NioLibrarySource
import io.github.roberbarras.racha.ui.app.PlatformHooks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

/** Desktop capabilities: the native folder dialog of the session (KDE/GNOME), Swing as a fallback. */
class DesktopPlatform(
    override val player: DesktopPlayerHooks? = null,
    override val resources: ResourceHooks? = DesktopResourceHooks(),
    override val appVersion: String = System.getProperty("racha.version") ?: "dev",
    override val deepLinks: Flow<String> = emptyFlow(),
) : PlatformHooks {
    override val detectedUiScale: Float get() = DesktopUiScale.detected

    override val hardwareDecodingSwitch: Boolean get() = player != null
    override val keyboardShortcuts: Boolean get() = true

    override suspend fun saveTextFile(suggestedName: String, text: String): Boolean = withContext(Dispatchers.IO) {
        val suggested = Path.of(System.getProperty("user.home"), suggestedName).toString()
        val chosen = native("kdialog", "--getsavefilename", suggested, "*." + suggestedName.substringAfterLast('.', "json"))
            ?: native("zenity", "--file-selection", "--save", "--confirm-overwrite", "--filename=$suggested")
            ?: swingFile(save = true, suggested)
            ?: return@withContext false
        runCatching { Files.writeString(Path.of(chosen), text) }.isSuccess
    }

    override suspend fun openTextFile(): String? = withContext(Dispatchers.IO) {
        val home = System.getProperty("user.home")
        val chosen = native("kdialog", "--getopenfilename", home, "*.json")
            ?: native("zenity", "--file-selection", "--file-filter=*.json", "--filename=$home/")
            ?: swingFile(save = false, home)
            ?: return@withContext null
        val path = Path.of(chosen)
        if (!Files.isRegularFile(path) || Files.size(path) > MAX_IMPORT_BYTES) return@withContext null
        runCatching { Files.readString(path) }.getOrNull()
    }

    private fun swingFile(save: Boolean, suggested: String): String? {
        var result: String? = null
        SwingUtilities.invokeAndWait {
            val chooser = JFileChooser().apply { selectedFile = java.io.File(suggested) }
            val answer = if (save) chooser.showSaveDialog(null) else chooser.showOpenDialog(null)
            if (answer == JFileChooser.APPROVE_OPTION) result = chooser.selectedFile.absolutePath
        }
        return result
    }

    override suspend fun pickFolder(): String? = withContext(Dispatchers.IO) {
        val home = System.getProperty("user.home")
        val chosen = native("kdialog", "--getexistingdirectory", home)
            ?: native("zenity", "--file-selection", "--directory", "--filename=$home/")
            ?: swing()
        chosen?.let(Path::of)?.takeIf(Files::isDirectory)?.let(NioLibrarySource::locationOf)
    }

    /** Runs a dialog tool; null if missing or cancelled. */
    private fun native(vararg command: String): String? = runCatching {
        val process = ProcessBuilder(*command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process.waitFor(10, TimeUnit.MINUTES)
        if (process.exitValue() != 0) return@runCatching null
        process.inputStream.bufferedReader().readText().trim().ifEmpty { null }
    }.getOrNull()

    private fun swing(): String? {
        var result: String? = null
        SwingUtilities.invokeAndWait {
            val chooser = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) result = chooser.selectedFile.absolutePath
        }
        return result
    }

    private companion object {
        const val MAX_IMPORT_BYTES = 1L shl 20
    }
}
