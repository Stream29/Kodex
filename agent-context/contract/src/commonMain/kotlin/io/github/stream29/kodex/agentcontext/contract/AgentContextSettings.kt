package io.github.stream29.kodex.agentcontext.contract

import io.github.stream29.kodex.utils.shellclient.ShellSettings
import kotlinx.io.files.Path

/**
 * Minimal application-wide settings required to resolve Agent context.
 *
 * A frontend or backend settings model may implement this interface without
 * exposing its concrete type or unrelated settings to AgentState.
 *
 * @property agentsHome Root directory used for Agents-level AGENTS and skills.
 * @property kodexHome Kodex-owned application data directory used for its
 * AGENTS and skills. This is distinct from the external Codex data source.
 * @property shell Shell advertised in the Agent environment context.
 */
public interface AgentContextSettings : ShellSettings {
    public val agentsHome: Path

    public val kodexHome: Path
}
