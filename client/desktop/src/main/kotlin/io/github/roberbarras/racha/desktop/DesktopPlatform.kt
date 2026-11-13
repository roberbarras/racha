package io.github.roberbarras.racha.desktop

import io.github.roberbarras.racha.files.NioLibrarySource
import io.github.roberbarras.racha.ui.app.PlatformHooks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

/** Desktop capabilities: the native folder dialog of the session (KDE/GNOME), Swing as a fallback. */
class DesktopPlatform : PlatformHooks {
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
}
