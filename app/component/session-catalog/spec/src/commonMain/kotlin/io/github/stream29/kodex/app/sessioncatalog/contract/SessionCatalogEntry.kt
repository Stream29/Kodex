package io.github.stream29.kodex.app.sessioncatalog.contract

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Lightweight persisted Session summary that does not open its runtime.
 *
 * Render identity by [sessionIndex], an optional human label by [threadName],
 * and archive, running and activity flags independently; none implies another.
 * Missing timestamps mean unavailable metadata, not an epoch value.
 *
 * @throws IllegalArgumentException if the index is negative or the name is blank.
 */
@Serializable
public data class SessionCatalogEntry(
    public val sessionIndex: Int,
    public val threadName: String? = null,
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
) {
    init {
        require(sessionIndex >= 0) {
            "A Session catalog index must not be negative."
        }
        require(threadName == null || threadName.isNotBlank()) {
            "A Session catalog thread name must be null or non-blank."
        }
    }
}
