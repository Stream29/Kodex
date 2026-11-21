package io.github.stream29.kodex.app.sessioncatalog.contract

import kotlinx.coroutines.flow.Flow

/** Only catalog reads; no Agent runtime is acquired to populate the panel. */
public interface SessionGroupReads {
    public fun getSessionGroupsNonceFlow(): Flow<Long>
    public suspend fun listSessionGroups(): List<SessionGroupSummary>
    public fun getSessionGroupNonceFlow(cwd: String?): Flow<Long>
    public suspend fun listSessionsInGroup(cwd: String?, includeArchived: Boolean, limit: Int?): SessionGroupPage
}

/** Null focus (no Agent) is distinct from a focused Agent whose cwd is unset. */
public data class SessionGroupFocus(public val cwd: String?, public val agentId: Int)

/** Local choices survive panel unmount; snapshots and subscriptions do not. */
public data class SessionGroupsState(
    public val groups: List<SessionGroupSummary>? = null,
    public val pages: Map<String?, SessionGroupPage> = emptyMap(),
    public val manualExpansion: Map<String?, Boolean> = emptyMap(),
    public val expandedLimitGroups: Set<String?> = emptySet(),
    public val showArchivedGroups: Set<String?> = emptySet(),
) {
    public fun isExpanded(cwd: String?, focus: SessionGroupFocus?): Boolean =
        manualExpansion[cwd]
            ?: ((focus != null && focus.cwd == cwd) || groups.orEmpty().take(2).any { it.cwd == cwd })

    public fun visibleEntries(cwd: String?): List<SessionCatalogEntry> {
        val entries = pages[cwd]?.entries.orEmpty()
        val visible = if (cwd in showArchivedGroups) entries else entries.filterNot { it.archived }
        return if (cwd in expandedLimitGroups) visible else visible.take(5)
    }
}
