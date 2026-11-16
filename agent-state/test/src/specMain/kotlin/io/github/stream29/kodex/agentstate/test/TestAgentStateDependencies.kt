package io.github.stream29.kodex.agentstate.test

import io.github.stream29.kodex.agentcontext.contract.AgentContextSettings
import kotlinx.coroutines.flow.StateFlow

/** Fixed application context for tests that do not exercise context discovery. */
public expect val TestAgentContextSettings: StateFlow<AgentContextSettings>
