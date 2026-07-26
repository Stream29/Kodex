package io.github.stream29.codex.lite.agentcontext.prefix.contract

import io.github.stream29.codex.lite.utils.shellclient.ShellSettings
import kotlinx.io.files.Path

/**
 * Minimal application-wide settings view required by an
 * [AgentContextPrefixProvider].
 *
 * A frontend or backend settings model may implement this interface without
 * exposing its concrete type or unrelated settings to the Agent context layer.
 *
 * @property codexHome Root directory used for user-level AGENTS.md discovery.
 * @property shell Shell advertised in the Agent environment context.
 */
public interface AgentContextSettings : ShellSettings {
    public val codexHome: Path
}
