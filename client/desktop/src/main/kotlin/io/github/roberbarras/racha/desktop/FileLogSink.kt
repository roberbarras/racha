package io.github.roberbarras.racha.desktop

import io.github.roberbarras.racha.application.shared.AppLog
import io.github.roberbarras.racha.domain.preferences.LogLevel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant

/** The app log on Linux (spec §9 "nivel de log"): `~/.cache/racha/racha.log`, one previous file kept, 1 MB each. */
class FileLogSink(private val file: Path) : AppLog.Sink {
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
        if (level <= LogLevel.WARN) System.err.print("racha $level $tag: $message\n")
    }

    private companion object {
        const val MAX_BYTES = 1L shl 20
    }
}
