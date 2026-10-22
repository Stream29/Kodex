@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.app.settings

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

public actual class SettingsUpdateQueue actual constructor(
    commandScope: CoroutineScope,
    private val defaultReportError: ((Throwable) -> Unit)?,
) {
    private class SettingsCommand(
        val block: suspend () -> Unit,
        val reportError: ((Throwable) -> Unit)?,
    )

    private val commands = Channel<SettingsCommand>(Channel.UNLIMITED)
    private var closed: Boolean = false
    private var terminalFailure: Throwable? = null
    public actual var drained: Boolean = false
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

    public actual fun submit(
        reportError: ((Throwable) -> Unit)?,
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

    public actual fun close(onClosed: ((drained: Boolean) -> Unit)?) {
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
    KotlinLogging.logger {}
}
