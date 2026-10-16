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
 * Closing rejects new work and drains accepted writes while the consumer is alive.
 * A terminal consumer cancellation is not a successful drain and rejects later admission.
 */
public class SettingsUpdateQueue(
    commandScope: CoroutineScope,
    private val defaultReportError: ((Throwable) -> Unit)? = null,
) {
    private class SettingsCommand(
        val block: suspend () -> Unit,
        val reportError: ((Throwable) -> Unit)?,
    )

    private val commands = Channel<SettingsCommand>(Channel.UNLIMITED)
    private var closed: Boolean = false
    private var terminalFailure: Throwable? = null
    public var drained: Boolean = false
        private set

    private val worker = commandScope.launch {
        try {
            for (command in commands) {
                try {
                    command.block()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    reportFailure(failure, command.reportError, "Failed to persist a Settings update.")
                }
            }
            drained = true
        } finally {
            // Release pending closures even if the parent survives a command-local cancellation.
            commands.cancel()
        }
    }

    init {
        worker.invokeOnCompletion { failure ->
            terminalFailure = failure
            commands.cancel()
        }
    }

    public fun submit(
        reportError: ((Throwable) -> Unit)? = null,
        block: suspend () -> Unit,
    ): Boolean {
        if (closed) return false
        val command = SettingsCommand(block, reportError)
        if (!worker.isActive || commands.trySend(command).isFailure) {
            val failure = IllegalStateException("The Settings update consumer is no longer available.", terminalFailure)
            reportFailure(failure, reportError, "Failed to enqueue a Settings update.")
            return false
        }
        return true
    }

    private fun reportFailure(failure: Throwable, reportError: ((Throwable) -> Unit)?, message: String) {
        val reporter = reportError ?: defaultReportError
        if (reporter == null) {
            logger.error(failure) { message }
        } else {
            reporter(failure)
        }
    }

    /** [onClosed] is cleanup, invoked even after cancellation; its flag is true only on normal drain. */
    public fun close(onClosed: ((drained: Boolean) -> Unit)? = null) {
        if (!closed) {
            closed = true
            commands.close()
        }
        onClosed?.let { callback ->
            worker.invokeOnCompletion {
                callback(drained)
            }
        }
    }
}

private val logger by lazy {
    KotlinLogging.logger {}.global()
}
