@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentcontext.prefix.impl

import io.github.stream29.kodex.agentcontext.contract.AgentContextSettings
import io.github.stream29.kodex.agentcontext.prefix.contract.AgentContextPrefix
import io.github.stream29.kodex.openai.KodexAgentSettings
import kotlinx.coroutines.flow.StateFlow

/**
 * Resolves application-wide and Agent-specific data into one request prefix.
 *
 * AgentState owns this helper rather than accepting an executable context
 * provider. Each [resolve] call captures one complete [contextSettings]
 * snapshot together with the supplied Agent settings.
 */
public expect class AgentContextPrefixResolver(
    contextSettings: StateFlow<AgentContextSettings>,
) {
    /** Captures global state and resolves Agent-specific context for [settings]. */
    public suspend fun resolve(settings: KodexAgentSettings, uri: String): AgentContextPrefix
}
