package io.github.stream29.kodex.tool.contract

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.openai.ToolSpec

/**
 * Creates a resource-free [Tool] that accepts one concrete pending clean-event
 * shape. [select] keeps the downcast at the contract boundary so tool business
 * logic receives its typed pending model directly.
 */
public fun <Pending : PendingToolEvent> typedTool(
    spec: ToolSpec,
    select: (PendingToolEvent) -> Pending?,
    handler: suspend (Pending) -> StableCleanEvent.CompletedTool,
): Tool =
    object : Tool {
        override val spec: ToolSpec = spec

        override suspend fun handle(pending: PendingToolEvent): StableCleanEvent.CompletedTool {
            val typedPending = requireNotNull(select(pending)) {
                "Tool $spec cannot handle pending event ${pending::class.simpleName}."
            }
            return handler(typedPending)
        }

        override fun close(): Unit = Unit
    }
