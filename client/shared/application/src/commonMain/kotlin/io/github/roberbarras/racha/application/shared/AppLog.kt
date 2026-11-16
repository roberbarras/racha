package io.github.roberbarras.racha.application.shared

import io.github.roberbarras.racha.domain.preferences.LogLevel

/**
 * The app's log (spec §9 "Avanzado: nivel de log"). Each platform installs a [sink] (Linux: a file next to the cache;
 * Android: logcat) and keeps [level] in line with the setting; until then nothing is written.
 */
object AppLog {
    fun interface Sink {
        fun write(level: LogLevel, tag: String, message: String, error: Throwable?)
    }

    @Volatile var sink: Sink? = null

    @Volatile var level: LogLevel = LogLevel.INFO

    fun error(tag: String, message: String, error: Throwable? = null) = log(LogLevel.ERROR, tag, message, error)

    fun warn(tag: String, message: String, error: Throwable? = null) = log(LogLevel.WARN, tag, message, error)

    fun info(tag: String, message: String) = log(LogLevel.INFO, tag, message, null)

    fun debug(tag: String, message: String) = log(LogLevel.DEBUG, tag, message, null)

    /** ERROR < WARN < INFO < DEBUG: a level shows itself and everything more severe. */
    fun log(level: LogLevel, tag: String, message: String, error: Throwable?) {
        if (level.ordinal > this.level.ordinal) return
        runCatching { sink?.write(level, tag, message, error) }
    }
}
