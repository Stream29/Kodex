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
) {
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var closed: Boolean = false

    private val worker = commandScope.launch {
        for (command in commands) {
            try {
                command()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                logger.error(failure) {
                    "Failed to persist a Settings update."
                }
            }
        }
    }

    fun submit(block: suspend () -> Unit) {
        if (closed) return
        commands.trySend(block).exceptionOrNull()?.let { failure ->
            logger.error(failure) {
                "Failed to enqueue a Settings update."
            }
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
