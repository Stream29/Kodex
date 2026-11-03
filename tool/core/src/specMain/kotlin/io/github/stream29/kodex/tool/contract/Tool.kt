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
     * The returned event owns the model-facing call/output projection. Runtime
     * consumers must derive protocol items from it instead of maintaining a
     * second raw output representation.
     * Recoverable execution failures produce a matching failed completion;
     * coroutine cancellation remains a cancellation signal and propagates.
     */
    public suspend fun handle(pending: PendingToolEvent): StableCleanEvent.CompletedTool
}

/**
 * Creates a resource-free [Tool] that accepts one concrete pending clean-event
 * shape. [select] keeps the downcast at the contract boundary so tool business
 * logic receives its typed pending model directly.
 *
 */
public expect fun <Pending : PendingToolEvent> typedTool(
    spec: ToolSpec,
    select: (PendingToolEvent) -> Pending?,
    handler: suspend (Pending) -> StableCleanEvent.CompletedTool,
): Tool

/**
 * Executes a tool operation and converts a non-cancellation exception to a
 * matching failed completion. Cancellation and failures in the conversion
 * itself propagate to the caller.
 */
public expect suspend fun toolCompletionOrFailure(
    pending: PendingToolEvent,
    operation: suspend () -> StableCleanEvent.CompletedTool,
): StableCleanEvent.CompletedTool
