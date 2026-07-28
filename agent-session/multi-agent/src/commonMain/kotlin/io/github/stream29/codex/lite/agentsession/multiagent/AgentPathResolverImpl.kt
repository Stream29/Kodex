package io.github.stream29.codex.lite.agentsession.multiagent

import io.github.stream29.codex.lite.agentsession.contract.AgentPathResolver
import io.github.stream29.codex.lite.agentsession.contract.CodexAgentSession

/**
 * Resolves model-facing Agent paths against the current recursive Session tree.
 *
 * Paths are read from each child Agent's latest `threadName` for every
 * operation. The resolver deliberately keeps no path cache, so renaming an
 * Agent becomes visible without an explicit refresh.
 */
public class AgentPathResolverImpl(
    private val rootSession: CodexAgentSession,
) : AgentPathResolver {
    override suspend fun resolveOrNull(path: String): CodexAgentSession? {
        if (!path.startsWith('/')) return null
        val segments = path.drop(1).split('/')
        if (segments.first() != RootAgentName) return null
        return rootSession.resolveDescendantOrNull(segments.drop(1))
    }
}

/** @return The matching descendant, or `null` when the next path segment is absent. */
private suspend fun CodexAgentSession.resolveDescendantOrNull(
    segments: List<String>,
): CodexAgentSession? {
    val name = segments.firstOrNull() ?: return this
    val child = subagents.list().firstNotNullOfOrNull { entryIndex ->
        subagents.open(entryIndex).takeIf { session ->
            session.agentNameOrNull() == name
        }
    } ?: return null
    return child.resolveDescendantOrNull(segments.drop(1))
}

/** @return The latest Agent name, or `null` when this Session is not initialized. */
private suspend fun CodexAgentSession.agentNameOrNull(): String? {
    val settingsIndex = storage.settings.latestIndex()
    if (settingsIndex < 0) return null
    return storage.settings[settingsIndex].threadName.substringAfterLast('/')
}

private const val RootAgentName: String = "root"
