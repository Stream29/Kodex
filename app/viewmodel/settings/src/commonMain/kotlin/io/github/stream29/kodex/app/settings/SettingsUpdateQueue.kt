package io.github.stream29.kodex.app.settings

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.utils.logging.global
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Serializes immediate Settings writes in a scope that outlives popup disposal.
 *
 * Closing rejects new work and drains already accepted writes.
 */
internal class SettingsUpdateQueue(
    commandScope: CoroutineScope,
    private val defaultReportError: ((Throwable) -> Unit)? = null,
) {
    private class SettingsCommand(
        val block: suspend () -> Unit,
        val reportError: ((Throwable) -> Unit)?,
    )

    private val commands = Channel<SettingsCommand>(Channel.UNLIMITED)
    private var closed: Boolean = false

    private val worker = commandScope.launch {
        for (command in commands) {
            try {
                command.block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                reportFailure(failure, command.reportError, "Failed to persist a Settings update.")
            }
        }
    }

    fun submit(
        reportError: ((Throwable) -> Unit)? = null,
        block: suspend () -> Unit,
    ) {
        if (closed) return
        val command = SettingsCommand(block, reportError)
        commands.trySend(command).exceptionOrNull()?.let { failure ->
            reportFailure(failure, reportError, "Failed to enqueue a Settings update.")
        }
    }

    private fun reportFailure(failure: Throwable, reportError: ((Throwable) -> Unit)?, message: String) {
        val reporter = reportError ?: defaultReportError
        if (reporter == null) {
            logger.error(failure) { message }
        } else {
            reporter(failure)
        }
    }

    fun close(onDrained: (() -> Unit)? = null) {
        if (!closed) {
            closed = true
            commands.close()
        }
        onDrained?.let { callback ->
            worker.invokeOnCompletion {
                callback()
            }
        }
    }
}

private val logger by lazy {
    KotlinLogging.logger {}.global()
}
