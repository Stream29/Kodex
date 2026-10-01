package io.github.stream29.kodex.agentstorage.cleanmodels.unstable

import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import io.github.stream29.kodex.utils.applypatch.Patch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Parsed `apply_patch` call waiting for execution. */
@Serializable
@SerialName("apply_patch")
public data class PendingPatchToolEvent(
    @SerialName("call_id")
    override val callId: String,
    @SerialName("item_id")
    override val itemId: ResponseItemId? = null,
    public val diff: Patch,
) : PendingToolEvent {
    override val toolName: String = "apply_patch"
    override val toolNamespace: String? = null

    override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(
            ResponseItem.CustomToolCall(
                id = itemId,
                callId = callId,
                name = "apply_patch",
                input = diff.patch,
            ),
        )
}
