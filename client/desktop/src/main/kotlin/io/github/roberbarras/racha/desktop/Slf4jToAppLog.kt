package io.github.roberbarras.racha.desktop

import io.github.roberbarras.racha.application.shared.AppLog
import io.github.roberbarras.racha.domain.preferences.LogLevel
import org.slf4j.ILoggerFactory
import org.slf4j.IMarkerFactory
import org.slf4j.Logger
import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.helpers.LegacyAbstractLogger
import org.slf4j.helpers.MessageFormatter
import org.slf4j.helpers.NOPMDCAdapter
import org.slf4j.spi.MDCAdapter
import org.slf4j.spi.SLF4JServiceProvider
import java.util.concurrent.ConcurrentHashMap

/**
 * Libraries that log through SLF4J (Ktor, OkHttp…) write to the app log, at its level, instead of nowhere (the
 * "No SLF4J providers were found" warning). Their DEBUG/TRACE only with `RACHA_LOG_LIBS=debug` on top of verbose mode.
 */
class Slf4jToAppLog : SLF4JServiceProvider {
    private val loggers = ConcurrentHashMap<String, Logger>()
    private val factory = ILoggerFactory { name -> loggers.getOrPut(name) { Bridge(name) } }
    private val markers = BasicMarkerFactory()
    private val mdc = NOPMDCAdapter()

    override fun getLoggerFactory(): ILoggerFactory = factory
    override fun getMarkerFactory(): IMarkerFactory = markers
    override fun getMDCAdapter(): MDCAdapter = mdc
    override fun getRequestedApiVersion(): String = "2.0.99"
    override fun initialize() = Unit

    private companion object {
        val librariesDebug = System.getenv("RACHA_LOG_LIBS").equals("debug", ignoreCase = true)
    }

    private class Bridge(private val loggerName: String) : LegacyAbstractLogger() {
        init {
            name = loggerName
        }

        private val tag = loggerName.substringAfterLast('.')

        // Libraries' DEBUG (every SQLite statement, every HTTP header…) only with RACHA_LOG_LIBS=debug.
        private fun on(level: LogLevel) = level.ordinal <= AppLog.level.ordinal && (level != LogLevel.DEBUG || librariesDebug)

        override fun isTraceEnabled() = on(LogLevel.DEBUG)
        override fun isDebugEnabled() = on(LogLevel.DEBUG)
        override fun isInfoEnabled() = on(LogLevel.INFO)
        override fun isWarnEnabled() = on(LogLevel.WARN)
        override fun isErrorEnabled() = on(LogLevel.ERROR)

        override fun getFullyQualifiedCallerName(): String? = null

        override fun handleNormalizedLoggingCall(level: Level, marker: Marker?, pattern: String?, arguments: Array<out Any?>?, throwable: Throwable?) {
            val message = MessageFormatter.basicArrayFormat(pattern, arguments)
            val ours = when (level) {
                Level.ERROR -> LogLevel.ERROR
                Level.WARN -> LogLevel.WARN
                Level.INFO -> LogLevel.INFO
                Level.DEBUG, Level.TRACE -> LogLevel.DEBUG
            }
            AppLog.log(ours, tag, message, throwable)
        }
    }
}
