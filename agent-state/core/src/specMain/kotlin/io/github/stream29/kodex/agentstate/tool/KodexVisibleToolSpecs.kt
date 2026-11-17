package io.github.stream29.kodex.agentstate.tool

import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolCall
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ToolSpec

/**
 * Returns the static 5 direct tool definitions visible in every Responses API request:
 * `exec_command`, `apply_patch`, `read_media`, `update_plan`, and `await_event`.
 */
public expect fun visibleToolSpecs(): List<ToolSpec>

/** Converts one provider-issued local tool call at the AgentState boundary. */
public expect fun ResponseItem.ToolCall.toPendingToolEvent(): PendingToolCall
