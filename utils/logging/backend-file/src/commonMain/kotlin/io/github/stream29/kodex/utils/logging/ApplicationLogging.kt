@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.utils.logging

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import io.github.oshai.kotlinlogging.Appender
import io.github.oshai.kotlinlogging.DirectLoggerFactory
import io.github.oshai.kotlinlogging.KLoggingEvent
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.writeBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.IO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.buffered
import kotlinx.io.RawSink
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.random.Random

public actual suspend fun initializeLogging(kodexHome: Path, processName: String): ApplicationLoggingSession {
    val logDirectory = Path(kodexHome, LogDirectoryName)
    SystemCoroutineFileSystem.createDirectories(logDirectory)
    val writer = BackgroundRollingFileLogWriter(rollingFileLogWriterConfig(logDirectory, processName))
    val appender = KermitAppender(writer)

    KotlinLoggingConfiguration.logStartupMessage = false
    KotlinLoggingConfiguration.loggerFactory = DirectLoggerFactory
    KotlinLoggingConfiguration.direct.logLevel = Level.INFO
    KotlinLoggingConfiguration.direct.appender = appender
    return ApplicationLoggingSession(writer)
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

internal fun rollingFileLogWriterConfig(logDirectory: Path, processName: String = LogFileBaseName): RollingLogConfig =
    RollingLogConfig(
        logFileName = processName,
        logFilePath = logDirectory,
        rollOnSize = RollOnSizeBytes,
        maxLogFiles = MaxLogFiles,
    )

public actual class ApplicationLoggingSession internal constructor(private val writer: BackgroundRollingFileLogWriter) {
    public actual suspend fun close(): Unit = writer.close()
}

internal data class RollingLogConfig(
    val logFileName: String,
    val logFilePath: Path,
    val rollOnSize: Long,
    val maxLogFiles: Int,
)

internal class BackgroundRollingFileLogWriter(
    private val config: RollingLogConfig,
    private val openSink: (Path) -> RawSink = { SystemFileSystem.sink(it, append = true) },
) : LogWriter() {
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val scope = CoroutineScope(Dispatchers.IO)
    private val worker = scope.launch {
        try {
            writeQueuedLogs()
        } catch (_: Throwable) {
            // A failed log sink must never log recursively or strand its producers.
        } finally {
            queue.cancel()
        }
    }

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        queue.trySend(buildString {
            append(Clock.System.now())
            append(" $severity: ($tag) ")
            appendLine(message)
            throwable?.let { appendLine(it.stackTraceToString()) }
        })
    }

    suspend fun close() {
        queue.close()
        withContext(NonCancellable) {
            if (withTimeoutOrNull(2.seconds) { worker.join(); true } != true) queue.cancel()
            scope.cancel()
        }
    }

    private fun path(index: Int): Path = Path(config.logFilePath,
        if (index == 0) "${config.logFileName}.log" else "${config.logFileName}-$index.log")

    private fun roll() {
        SystemFileSystem.delete(path(config.maxLogFiles - 1), mustExist = false)
        for (index in (0 until config.maxLogFiles - 1).reversed()) {
            if (SystemFileSystem.exists(path(index))) SystemFileSystem.atomicMove(path(index), path(index + 1))
        }
    }

    private suspend fun writeQueuedLogs() {
        var size = SystemFileSystem.metadataOrNull(path(0))?.size ?: 0L
        if (size > config.rollOnSize) { roll(); size = 0L }
        var sink = openSink(path(0)).buffered()
        try {
            for (message in queue) {
                if (size > config.rollOnSize) {
                    sink.close()
                    roll()
                    sink = openSink(path(0)).buffered()
                    size = 0L
                }
                sink.writeString(message)
                size += message.encodeToByteArray().size
                sink.flush()
            }
        } finally {
            sink.close()
        }
    }
}

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
