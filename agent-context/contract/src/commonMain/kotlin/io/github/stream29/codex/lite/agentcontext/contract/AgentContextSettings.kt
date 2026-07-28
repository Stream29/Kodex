package io.github.stream29.codex.lite.agentcontext.contract

import io.github.stream29.codex.lite.utils.shellclient.ShellSettings
import kotlinx.io.files.Path

/**
 * Minimal application-wide settings required to resolve Agent context.
 *
 * A frontend or backend settings model may implement this interface without
 * exposing its concrete type or unrelated settings to AgentState.
 *
 * @property codexHome Root directory used for user-level AGENTS.md discovery.
 * @property shell Shell advertised in the Agent environment context.
 */
public interface AgentContextSettings : ShellSettings {
    public val codexHome: Path
}
