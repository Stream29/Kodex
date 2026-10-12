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
     */
    public suspend fun handle(pending: PendingToolEvent): StableCleanEvent.CompletedTool
}
