package io.github.stream29.kodex.utils.logging

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import co.touchlab.kermit.io.RollingFileLogWriter
import co.touchlab.kermit.io.RollingFileLogWriterConfig
import io.github.oshai.kotlinlogging.Appender
import io.github.oshai.kotlinlogging.DirectLoggerFactory
import io.github.oshai.kotlinlogging.KLoggingEvent
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.writeBytes
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.random.Random

public actual suspend fun initializeLogging(kodexHome: Path, processName: String): Unit {
    val logDirectory = Path(kodexHome, LogDirectoryName)
    SystemCoroutineFileSystem.createDirectories(logDirectory)
    val appender = KermitAppender(
        RollingFileLogWriter(rollingFileLogWriterConfig(logDirectory, processName)),
    )

    KotlinLoggingConfiguration.logStartupMessage = false
    KotlinLoggingConfiguration.loggerFactory = DirectLoggerFactory
    KotlinLoggingConfiguration.direct.logLevel = Level.INFO
    KotlinLoggingConfiguration.direct.appender = appender
}

public actual suspend fun writeCliTerminalFailure(kodexHome: Path, processId: Long, failure: Throwable) {
    val directory = Path(kodexHome, LogDirectoryName)
    SystemCoroutineFileSystem.createDirectories(directory)
    // Numeric suffixes are owned by the existing process-log retention policy.
    val path = Path(directory, "Cli-$processId-${Random.nextLong(1, Long.MAX_VALUE)}.log")
    var created = false
    try {
        SystemCoroutineFileSystem.useSink(path, mustCreate = true) { sink ->
            created = true
            SystemCoroutineFileSystem.protectPrivateFile(path)
            sink.writeBytes(("CLI invocation failed\n" + failure.stackTraceToString() + "\n").encodeToByteArray())
            sink.flush()
        }
    } catch (writeFailure: Throwable) {
        if (created) {
            try {
                withContext(NonCancellable) { SystemCoroutineFileSystem.delete(path, mustExist = false) }
            } catch (cleanup: Throwable) {
                if (cleanup !== writeFailure) writeFailure.addSuppressed(cleanup)
            }
        }
        throw writeFailure
    }
}

internal fun rollingFileLogWriterConfig(logDirectory: Path, processName: String = LogFileBaseName): RollingFileLogWriterConfig =
    RollingFileLogWriterConfig(
        logFileName = processName,
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
internal const val LogFileBaseName: String = "Kodex"
internal const val RollOnSizeBytes: Long = 10L * 1024L * 1024L
internal const val MaxLogFiles: Int = 5
