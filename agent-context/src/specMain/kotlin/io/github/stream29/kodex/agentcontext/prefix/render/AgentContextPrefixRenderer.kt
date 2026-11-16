package io.github.stream29.kodex.agentcontext.prefix.render

import io.github.stream29.kodex.agentcontext.prefix.contract.AgentContextPrefix
import io.github.stream29.kodex.openai.ResponseItem

/**
 * Renders current host context into the transient prefix of one Responses request.
 *
 * Emits Developer-role sections for the flat `kodex <domain> <action>` CLI toolset
 * (filtered by [AgentContextPrefix.requestUserInputMode] so `NoQuestion` omits `kodex ask`
 * and disallows asking the user), `kodex tool list`, `read_media`, `await_event`, and
 * [AgentContextPrefix.availableSkills], followed by contextual User-role sections for
 * `AGENTS.md` and `<environment_context>`.
 *
 * The returned items are intentionally never written to agent storage.
 */
public expect fun AgentContextPrefix.render(): List<ResponseItem.HistoryItem>
