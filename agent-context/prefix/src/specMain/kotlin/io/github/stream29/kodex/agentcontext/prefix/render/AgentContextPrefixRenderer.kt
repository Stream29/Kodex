package io.github.stream29.kodex.agentcontext.prefix.render

import io.github.stream29.kodex.agentcontext.prefix.contract.AgentContextPrefix
import io.github.stream29.kodex.openai.ResponseItem

/**
 * Renders current host context into the transient prefix of one Responses request.
 *
 * The returned items are intentionally never written to agent storage.
 */
public expect fun AgentContextPrefix.render(): List<ResponseItem.HistoryItem>
