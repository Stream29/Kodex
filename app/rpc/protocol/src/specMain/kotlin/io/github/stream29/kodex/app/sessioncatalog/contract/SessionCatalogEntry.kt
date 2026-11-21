package io.github.stream29.kodex.app.sessioncatalog.contract

import kotlinx.serialization.Serializable
import kotlinx.serialization.Required
import kotlin.time.Instant

/**
 * Lightweight persisted Session summary that does not open its runtime.
 *
 * Render identity by [sessionIndex], an optional human label by [name],
 * and archive, running and activity flags independently; none implies another.
 * Missing timestamps mean unavailable metadata, not an epoch value.
 *
 * @property sessionIndex Current backend addressing number, not permanent identity across deletion/reuse.
 * @property name Optional non-blank title; null renders as `Session <index>`.
 * @property archived Persisted archive flag; independent of running/owner residency.
 * @throws IllegalArgumentException if the index is negative or the name is blank.
 */
@Serializable
public data class SessionCatalogEntry(
    public val sessionIndex: Int,
    public val name: String? = null,
    /** Exact timestamp at index zero; null when that record is absent. */
    public val createdAt: Instant? = null,
    /** Latest persisted timestamp, also used for catalog activity ordering and relative labels. */
    public val updatedAt: Instant? = null,
    public val archived: Boolean = false,
    /**
     * Root Agent runningTurn presence sampled when querying the catalog, not a live subscription.
     * Includes ordinary execution and manual compaction, not history operations or frontend waits.
     * Independent of frontend tabs, archive status and owner residency; not a TTL eviction gate.
     * The default preserves existing callers; an RPC backend must supply its actual observation.
     */
    public val running: Boolean = false,
    /**
     * Backend Session owner residency sampled when querying the catalog, independently of [running].
     * An idle open Session is active; a frontend tab or an archived entry need not be.
     * This snapshot is not a keepalive or permission for later live access.
     */
    public val isActive: Boolean = false,
    /** Persisted root AgentId; distinct from the directory key [sessionIndex]. */
    @Required public val rootAgentId: Int = sessionIndex,
) {
    init {
        require(sessionIndex >= 0) {
            "A Session catalog index must not be negative."
        }
        require(rootAgentId >= 0) { "A root Agent ID must not be negative." }
        require(name == null || name.isNotBlank()) {
            "A Session catalog name must be null or non-blank."
        }
    }
}
