package io.github.stream29.kodex.agentsession.contract

import io.github.stream29.kodex.agentstorage.contract.latestValue

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
    public suspend fun resolveOrNull(path: String): KodexAgentSession?
}

/** Returns the root Agent Session exposed by this resolver. */
public suspend fun AgentPathResolver.rootSession(): KodexAgentSession =
    requireNotNull(resolveOrNull(RootAgentPath)) {
        "Agent path resolver does not expose $RootAgentPath."
    }

/** Returns [session]'s current canonical Agent path. */
public suspend fun AgentPathResolver.pathOf(session: KodexAgentSession): String =
    if (session.storage.id == rootSession().storage.id) {
        RootAgentPath
    } else {
        session.storage.settings.latestValue().threadName.also { path ->
            require(path.startsWith("$RootAgentPath/")) {
                "Subagent thread name must be a canonical Agent path: $path"
            }
        }
    }

/** Returns [session]'s parent, or `null` when [session] is the root Agent. */
public suspend fun AgentPathResolver.parentOf(session: KodexAgentSession): KodexAgentSession? {
    val path = pathOf(session)
    if (path == RootAgentPath) return null
    return resolveOrNull(path.substringBeforeLast('/'))
}

/** Returns initialized direct children of [session] in repository order. */
public suspend fun AgentPathResolver.listChild(session: KodexAgentSession): List<KodexAgentSession> =
    session.subagents.list().mapNotNull { entryIndex ->
        session.subagents.open(entryIndex).takeIf { child ->
            child.storage.settings.latestIndex() >= 0
        }
    }

private const val RootAgentPath: String = "/root"
