package io.github.stream29.kodex.app.session.contract

import io.github.stream29.kodex.app.agent.contract.AgentSettingsViewModel
import kotlinx.coroutines.flow.StateFlow

/**
 * Common UI-facing ViewModel implemented by a virtual or persisted Session.
 *
 * This contract only unifies frontend presentation and root-settings commands.
 * It does not give both variants the same persistence identity or lifecycle.
 */
public sealed interface SessionViewModel :
    AgentSettingsViewModel,
    AutoCloseable {
    /** Root/tab display name, independent of selected-subagent settings. */
    public val name: StateFlow<String>

    public suspend fun rename(name: String): Unit

    override fun close(): Unit
}
