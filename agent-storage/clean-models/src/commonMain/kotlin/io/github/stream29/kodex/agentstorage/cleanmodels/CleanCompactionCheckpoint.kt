package io.github.stream29.kodex.agentstorage.cleanmodels

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.RemoteCompactionV2RetainedItem
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.openai.ResponseItem
import kotlinx.serialization.Serializable

/**
 * Clean context-window checkpoint stored on the compaction timeline.
 *
 * The replacement prefix and window lineage live here. Model input continues
 * with stable events from [historyBaseIndex], including any
 * [StableCleanEvent.ContextCompaction] at the checkpoint boundary.
 */
@Serializable
public data class CleanCompactionCheckpoint(
    public val prefix: List<RemoteCompactionV2RetainedItem>,
    public val historyBaseIndex: Int,
    public val windowNumber: Long,
    public val firstWindowId: String,
    public val previousWindowId: String? = null,
    public val windowId: String,
) {
    public fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        prefix.flatMap(RemoteCompactionV2RetainedItem::toResponseHistoryItems)
}

/** Returns the provider-facing request-window identity for this checkpoint. */
public fun CleanCompactionCheckpoint.codexRequestWindowId(threadId: String): String =
    "$threadId:$windowNumber"
