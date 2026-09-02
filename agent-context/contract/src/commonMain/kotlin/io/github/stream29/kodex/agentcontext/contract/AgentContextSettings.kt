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

    public val codexHome: Path

    public val sources: AgentContextSourceSettings
}

/** Enablement and user-defined roots used by one context resolution snapshot. */
public data class AgentContextSourceSettings(
    public val agentsHomeEnabled: Boolean = true,
    public val kodexHomeEnabled: Boolean = true,
    public val codexHomeEnabled: Boolean = true,
    public val gitRootEnabled: Boolean = true,
    public val workingDirectoryEnabled: Boolean = true,
    public val customSources: List<AgentContextCustomSource> = emptyList(),
)

/** One user-defined context root retained in settings order. */
public data class AgentContextCustomSource(
    public val path: String,
    public val enabled: Boolean = true,
) {
    init {
        require(path.isNotBlank()) { "A custom context source path must not be blank." }
    }
}

/** One request's deduplicated global and project context roots. */
public data class AgentContextSourcePlan(
    public val globalRoots: List<Path>,
    public val projectRoots: List<Path>,
)
