package io.github.stream29.kodex.agentstorage.cleanmodels

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.openai.ResponseItem
import kotlinx.serialization.Serializable

/**
 * Clean context-window checkpoint stored on the compaction timeline.
 *
 * The stable timeline stores only an empty compaction marker at the same
 * index. The replacement prefix, provider compaction payload, and window
 * lineage live here exactly once.
 */
@Serializable
public data class CleanCompactionCheckpoint(
    public val prefix: List<StableCleanEvent>,
    public val compaction: ResponseItem.Compaction? = null,
    public val historyBaseIndex: Int,
    public val windowNumber: Long,
    public val firstWindowId: String,
    public val previousWindowId: String? = null,
    public val windowId: String,
) {
    public fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        prefix.flatMap(StableCleanEvent::toResponseHistoryItems) + listOfNotNull(compaction)
}

/** Returns the provider-facing request-window identity for this checkpoint. */
public fun CleanCompactionCheckpoint.codexRequestWindowId(threadId: String): String =
    "$threadId:$windowNumber"
