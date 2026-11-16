package io.github.stream29.kodex.tool.contract

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.openai.ToolSpec

/**
 * Non-generic executable tool contract used by the agent loop.
 *
 * The public boundary uses durable clean models. Implementations own their
 * close behavior because handlers may hold resources.
 */
public interface Tool : AutoCloseable {
    public val spec: ToolSpec

    /**
     * Executes [pending] and returns its sole canonical completion.
     *
     * Domain-expected recoverable failures return a matching failed completion;
     * unexpected exceptions propagate to the runtime caller for contextual
     * logging and conversion, while coroutine cancellation propagates unchanged.
     */
    public suspend fun handle(pending: PendingToolEvent): StableCleanEvent.CompletedTool
}

/**
 * Creates a resource-free [Tool] that accepts one concrete pending clean-event
 * shape. [select] keeps the downcast at the contract boundary so tool business
 * logic receives its typed pending model directly.
 */
public expect fun <Pending : PendingToolEvent> typedTool(
    spec: ToolSpec,
    select: (PendingToolEvent) -> Pending?,
    handler: suspend (Pending) -> StableCleanEvent.CompletedTool,
): Tool
