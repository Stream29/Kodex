package io.github.stream29.kodex.tool.contract

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolCall
import io.github.stream29.kodex.openai.ToolSpec
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException

/**
 * Creates a resource-free [Tool] that accepts one concrete pending clean-event
 * shape. [select] keeps the downcast at the contract boundary so tool business
 * logic receives its typed pending model directly.
 */
public actual fun <Pending : PendingToolCall> typedTool(
    spec: ToolSpec,
    select: (PendingToolCall) -> Pending?,
    handler: suspend (Pending) -> AgentCommit.CompletedTool,
): Tool =
    object : Tool {
        override val spec: ToolSpec = spec

        override suspend fun handle(pending: PendingToolCall): AgentCommit.CompletedTool {
            currentCoroutineContext().ensureActive()
            val typedPending = requireNotNull(select(pending)) {
                "Tool $spec cannot handle pending event ${pending::class.simpleName}."
            }
            return try {
                handler(typedPending).also { currentCoroutineContext().ensureActive() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                throw failure
            }
        }

        override fun close(): Unit = Unit
    }
