package io.github.roberbarras.racha.desktop.notifications

import io.github.roberbarras.racha.application.notifications.Notice
import io.github.roberbarras.racha.application.notifications.Notifier
import io.github.roberbarras.racha.application.shared.AppLog
import io.github.roberbarras.racha.ui.notifications.NoticeTexts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Desktop notifications through the session's notification service (org.freedesktop.Notifications: KDE, GNOME…),
 * called with `gdbus` (GLib, present almost everywhere) or `notify-send`. Without either, the notice goes to the log.
 */
class DesktopNotifier : Notifier {
    override suspend fun show(notice: Notice) {
        val text = NoticeTexts.of(notice)
        val shown = withContext(Dispatchers.IO) { run(gdbusCommand(text.title, text.body)) || run(listOf("notify-send", "-a", APP, "-i", ICON, text.title, text.body)) }
        if (!shown) AppLog.info("notify", "no notification service: ${text.title} — ${text.body}")
    }

    private fun run(command: List<String>): Boolean = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
    }.getOrDefault(false)

    companion object {
        private const val APP = "Racha"
        private const val ICON = "video-x-generic"

        /** `Notify(app, replaces 0, icon, summary, body, actions, hints, timeout -1)`, arguments as GVariant text. */
        internal fun gdbusCommand(title: String, body: String): List<String> = listOf(
            "gdbus", "call", "--session",
            "--dest", "org.freedesktop.Notifications",
            "--object-path", "/org/freedesktop/Notifications",
            "--method", "org.freedesktop.Notifications.Notify",
            quoted(APP), "0", quoted(ICON), quoted(title), quoted(body), "[]", "{}", "-1",
        )

        /** A GVariant string literal: nothing in the text can be read as another type or break the call. */
        internal fun quoted(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    }
}
