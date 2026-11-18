package io.github.roberbarras.racha.desktop

import io.github.roberbarras.racha.application.shared.AppLog
import io.github.roberbarras.racha.domain.preferences.LogLevel
import java.time.Duration
import java.time.Instant

/**
 * How long start-up takes, phase by phase, in the log (`startup: +1234 ms <phase>`), counted from the moment the
 * process started. With `RACHA_LOG=debug` (or `--verbose`) it also shows in the terminal.
 */
object Startup {
    private val processStart: Instant = ProcessHandle.current().info().startInstant().orElse(Instant.now())

    fun mark(phase: String) {
        AppLog.info("startup", "+${Duration.between(processStart, Instant.now()).toMillis()} ms $phase")
    }

    /**
     * The level asked for when launching, which wins over the one in Settings: `RACHA_LOG=error|warn|info|debug`, or
     * `--verbose` / `-v` for debug. Null: the setting decides, and only warnings and errors reach the terminal.
     */
    fun requestedLogLevel(args: Array<String>, env: String? = System.getenv("RACHA_LOG")): LogLevel? = when {
        "--verbose" in args || "-v" in args -> LogLevel.DEBUG
        else -> env?.trim()?.uppercase()?.let { name -> LogLevel.entries.firstOrNull { it.name == name } }
    }
}
