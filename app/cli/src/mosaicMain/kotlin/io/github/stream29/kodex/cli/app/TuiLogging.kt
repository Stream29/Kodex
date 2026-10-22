package io.github.stream29.kodex.cli.app

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import co.touchlab.kermit.io.RollingFileLogWriter
import co.touchlab.kermit.io.RollingFileLogWriterConfig
import io.github.oshai.kotlinlogging.Appender
import io.github.oshai.kotlinlogging.DirectLoggerFactory
import io.github.oshai.kotlinlogging.KLoggingEvent
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level
import io.github.stream29.kodex.backend.backendProcessAlive
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.osenvironment.processId
import kotlinx.io.files.Path

/** Installs local TUI diagnostics without invoking either backend logging library. */
internal suspend fun initializeTuiLogging(home: Path) {
    val directory = Path(home, "log")
    SystemCoroutineFileSystem.createDirectories(directory)
    val names = Regex("^(?:Backend|Tui|Cli)-([0-9]+)(?:-[0-9]+)?\\.log$")
    val inactive = linkedMapOf<Long, MutableList<Path>>()
    for (path in SystemCoroutineFileSystem.list(directory)) {
        val pid = names.matchEntire(path.name)?.groupValues?.get(1)?.toLongOrNull() ?: continue
        if (pid != processId() && !backendProcessAlive(pid)) inactive.getOrPut(pid) { mutableListOf() }.add(path)
    }
    val dated = inactive.entries.map { entry ->
        entry to (entry.value.map { SystemCoroutineFileSystem.fingerprintOrNull(it)?.lastModifiedAtNanoseconds ?: 0 }.maxOrNull() ?: 0)
    }.sortedByDescending { it.second }
    for ((entry, _) in dated.drop(8)) {
        entry.value.forEach { SystemCoroutineFileSystem.delete(it, mustExist = false) }
    }

    val appender = TuiFileAppender(
        RollingFileLogWriter(
            RollingFileLogWriterConfig(
                logFileName = "Tui-${processId()}",
                logFilePath = directory,
                rollOnSize = TuiLogRollOnSizeBytes,
                maxLogFiles = TuiMaxLogFiles,
            ),
        ),
    )
    KotlinLoggingConfiguration.logStartupMessage = false
    KotlinLoggingConfiguration.loggerFactory = DirectLoggerFactory
    KotlinLoggingConfiguration.direct.logLevel = Level.INFO
    KotlinLoggingConfiguration.direct.appender = appender
}

private class TuiFileAppender(private val writer: LogWriter) : Appender {
    override fun log(loggingEvent: KLoggingEvent) {
        val severity = when (loggingEvent.level) {
            Level.TRACE -> Severity.Verbose
            Level.DEBUG -> Severity.Debug
            Level.INFO -> Severity.Info
            Level.WARN -> Severity.Warn
            Level.ERROR -> Severity.Error
            Level.OFF -> return
        }
        writer.log(
            severity = severity,
            message = loggingEvent.renderTuiMessage(),
            tag = loggingEvent.loggerName,
            throwable = loggingEvent.cause,
        )
    }
}

private fun KLoggingEvent.renderTuiMessage(): String = buildString {
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

internal const val TuiLogRollOnSizeBytes: Long = 10L * 1024L * 1024L
internal const val TuiMaxLogFiles: Int = 5
