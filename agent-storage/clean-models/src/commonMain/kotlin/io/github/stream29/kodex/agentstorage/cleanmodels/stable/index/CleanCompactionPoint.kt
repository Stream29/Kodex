package io.github.stream29.kodex.agentstorage.cleanmodels.stable.index

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Context-window lineage marker stored on the index timeline.
 *
 * The initial point is stored at index `0`. Every later point is followed by a
 * work-timeline context-compaction event; pairing is enforced by the writer.
 */
@Serializable
@SerialName("compaction_point")
public data class CleanCompactionPoint(
    public val windowNumber: Long,
    public val firstWindowId: String,
    public val previousWindowId: String? = null,
    public val windowId: String,
) : CleanIndexEntry

/** Returns the provider-facing request-window identity for this point. */
public fun CleanCompactionPoint.codexRequestWindowId(threadId: String): String =
    "$threadId:$windowNumber"
