package io.github.stream29.kodex.agentstorage.cleanmodels.stable.index

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Context-window lineage marker stored on the index timeline.
 *
 * The initial point is stored at index `0`. Every later point is followed by a
 * work-timeline context-compaction event; pairing is enforced by the writer.
 *
 * The lineage fields belong to the settings timeline at the same index. This
 * entry is deliberately only a boundary marker so that the index timeline
 * does not duplicate settings data.
 */
@Serializable
@SerialName("compaction_point")
public data object CleanCompactionPoint : CleanIndexEntry
