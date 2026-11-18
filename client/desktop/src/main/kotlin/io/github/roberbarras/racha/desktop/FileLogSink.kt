package io.github.roberbarras.racha.desktop

import io.github.roberbarras.racha.application.shared.AppLog
import io.github.roberbarras.racha.domain.preferences.LogLevel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant

/**
 * The app log on Linux (spec §9 "nivel de log"): `~/.cache/racha/racha.log`, one previous file kept, 1 MB each.
 * Lines up to [terminal] also go to the terminal (warnings and errors by default; everything in verbose mode).
 */
class FileLogSink(private val file: Path, private val terminal: LogLevel = LogLevel.WARN) : AppLog.Sink {
    @Synchronized
    override fun write(level: LogLevel, tag: String, message: String, error: Throwable?) {
        runCatching {
            Files.createDirectories(file.parent)
            if (Files.exists(file) && Files.size(file) > MAX_BYTES) {
                Files.move(file, file.resolveSibling(file.fileName.toString() + ".1"), StandardCopyOption.REPLACE_EXISTING)
            }
            val line = buildString {
                append(Instant.now()).append(' ').append(level.name.padEnd(5)).append(' ').append(tag).append(": ").append(message).append('\n')
                error?.let { append(it.stackTraceToString()).append('\n') }
            }
            Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
        if (level <= terminal) {
            System.err.print("${java.time.LocalTime.now()} racha ${level.name.padEnd(5)} $tag: $message\n")
            if (terminal == LogLevel.DEBUG) error?.printStackTrace()
        }
    }

    private companion object {
        const val MAX_BYTES = 1L shl 20
    }
}
