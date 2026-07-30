package io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable

import io.github.stream29.codex.lite.openai.ResponseItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Hosted tool-search call awaiting its server-produced output. */
@Serializable
@SerialName("server_tool_search")
public data class PendingServerToolSearch(
    public val call: ResponseItem.ServerToolSearchCall,
) : UnstableCleanEvent {
    override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(call)
}
