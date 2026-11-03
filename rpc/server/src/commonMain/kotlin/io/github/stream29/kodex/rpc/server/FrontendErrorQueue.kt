package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.rpc.models.NotificationHook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backend-owned admission and execution for frontend error observations.
 * Admission does not guarantee completion if the backend shuts down.
 */
internal class FrontendErrorQueue(
    scope: CoroutineScope,
    private val hooks: () -> List<NotificationHook> = { emptyList() },
    private val runHook: suspend (List<NotificationHook>, String?) -> Unit,
) {
    internal enum class Admission { ADMITTED, OVERLOADED, CLOSED }

    private class Event(val message: String?, val hooks: List<NotificationHook>)

    private val events = Channel<Event>(capacity = 64)
    private val owner = SupervisorJob(scope.coroutineContext[Job])
    private val worker = CoroutineScope(scope.coroutineContext + owner).launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            for (event in events) {
                try {
                    runHook(event.hooks, event.message)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // An individual hook failure must not stop later events or expose the message.
                }
            }
        } finally {
            events.cancel()
        }
    }

    fun admit(message: String?): Admission {
        require(message == null || (message.length <= MAX_MESSAGE_BYTES && message.encodeToByteArray().size <= MAX_MESSAGE_BYTES)) {
            "Frontend error message exceeds the permitted size"
        }
        val result = events.trySend(Event(message, hooks().toList()))
        return when {
            result.isSuccess -> Admission.ADMITTED
            result.isClosed -> Admission.CLOSED
            else -> Admission.OVERLOADED
        }
    }

    suspend fun closeAndJoin() {
        withContext(NonCancellable) {
            events.cancel()
            owner.cancel()
            worker.join()
        }
    }

    private companion object {
        const val MAX_MESSAGE_BYTES = 8192
    }
}
