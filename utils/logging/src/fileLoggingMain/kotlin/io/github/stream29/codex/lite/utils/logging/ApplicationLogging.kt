package io.github.stream29.codex.lite.utils.logging

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import co.touchlab.kermit.io.RollingFileLogWriter
import co.touchlab.kermit.io.RollingFileLogWriterConfig
import io.github.oshai.kotlinlogging.Appender
import io.github.oshai.kotlinlogging.DirectLoggerFactory
import io.github.oshai.kotlinlogging.KLoggingEvent
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path

/** Configures process-wide logging before application services are created. */
public suspend fun initializeLogging(codexLiteHome: Path) {
    val logDirectory = Path(codexLiteHome, LogDirectoryName)
    SystemCoroutineFileSystem.createDirectories(logDirectory)
    val appender = KermitAppender(
        RollingFileLogWriter(rollingFileLogWriterConfig(logDirectory)),
    )

    KotlinLoggingConfiguration.logStartupMessage = false
    KotlinLoggingConfiguration.loggerFactory = DirectLoggerFactory
    KotlinLoggingConfiguration.direct.logLevel = Level.INFO
    KotlinLoggingConfiguration.direct.appender = appender
}

internal fun rollingFileLogWriterConfig(logDirectory: Path): RollingFileLogWriterConfig =
    RollingFileLogWriterConfig(
        logFileName = LogFileBaseName,
        logFilePath = logDirectory,
        rollOnSize = RollOnSizeBytes,
        maxLogFiles = MaxLogFiles,
    )

internal class KermitAppender(
    private val writer: LogWriter,
) : Appender {
    override fun log(loggingEvent: KLoggingEvent) {
        val severity = loggingEvent.level.toKermitSeverity() ?: return
        writer.log(
            severity = severity,
            message = loggingEvent.renderMessage(),
            tag = loggingEvent.loggerName,
            throwable = loggingEvent.cause,
        )
    }
}

private fun Level.toKermitSeverity(): Severity? =
    when (this) {
        Level.TRACE -> Severity.Verbose
        Level.DEBUG -> Severity.Debug
        Level.INFO -> Severity.Info
        Level.WARN -> Severity.Warn
        Level.ERROR -> Severity.Error
        Level.OFF -> null
    }

private fun KLoggingEvent.renderMessage(): String = buildString {
    marker?.let { marker ->
        append('[')
        append(marker.getName())
        append("] ")
    }
    message?.let(::append)
    payload?.takeIf(Map<*, *>::isNotEmpty)?.let { values ->
        if (isNotEmpty()) append(' ')
        append(values.entries.joinToString(prefix = "{", postfix = "}"))
    }
}

internal const val LogDirectoryName: String = "log"
internal const val LogFileBaseName: String = "CodexLite"
internal const val RollOnSizeBytes: Long = 10L * 1024L * 1024L
internal const val MaxLogFiles: Int = 5
