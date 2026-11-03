package io.github.stream29.kodex.tool.contract

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.agentstorage.cleanmodels.toFailedToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.openai.ToolSpec
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException

private val toolFailureLogger = KotlinLogging.logger {}

public actual suspend fun toolCompletionOrFailure(
    pending: PendingToolEvent,
    operation: suspend () -> StableCleanEvent.CompletedTool,
): StableCleanEvent.CompletedTool = try {
    operation().also { currentCoroutineContext().ensureActive() }
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (failure: Exception) {
    currentCoroutineContext().ensureActive()
    toolFailureLogger.error(failure) { "Tool execution failed for call ${pending.callId}." }
    pending.toFailedToolEvent("Tool execution failed.")
}

/**
 * Creates a resource-free [Tool] that accepts one concrete pending clean-event
 * shape. [select] keeps the downcast at the contract boundary so tool business
 * logic receives its typed pending model directly.
 */
public actual fun <Pending : PendingToolEvent> typedTool(
    spec: ToolSpec,
    select: (PendingToolEvent) -> Pending?,
    handler: suspend (Pending) -> StableCleanEvent.CompletedTool,
): Tool =
    object : Tool {
        override val spec: ToolSpec = spec

        override suspend fun handle(pending: PendingToolEvent): StableCleanEvent.CompletedTool =
            toolCompletionOrFailure(pending) {
                val typedPending = requireNotNull(select(pending)) {
                    "Tool $spec cannot handle pending event ${pending::class.simpleName}."
                }
                handler(typedPending)
            }

        override fun close(): Unit = Unit
    }
