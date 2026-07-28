package io.github.stream29.codex.lite.agentsession.contract

/**
 * Resolves model-facing Agent paths within one recursive Session tree.
 *
 * Implementations must observe the latest persisted Agent paths on every
 * operation so callers do not need an explicit refresh after a rename.
 */
public fun interface AgentPathResolver {
    /**
     * Resolves the canonical Agent [path].
     *
     * The path must be `/root` or begin with `/root/`.
     *
     * @return The current Agent Session at [path], or `null` when [path] is
     * malformed or no longer exists in the Session tree.
     */
    public suspend fun resolveOrNull(path: String): CodexAgentSession?
}
