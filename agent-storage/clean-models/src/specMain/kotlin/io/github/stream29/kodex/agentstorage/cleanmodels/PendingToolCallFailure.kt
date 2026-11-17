package io.github.stream29.kodex.agentstorage.cleanmodels

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolCall

/** Converts one pending local call into its matching durable failed tool event. */
public expect fun PendingToolCall.toFailedToolEvent(message: String): AgentCommit.CompletedTool
