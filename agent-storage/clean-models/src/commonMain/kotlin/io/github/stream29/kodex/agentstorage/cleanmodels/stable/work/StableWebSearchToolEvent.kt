package io.github.stream29.kodex.agentstorage.cleanmodels.stable.work

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.stableFunctionCall
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.stableTextOutput
import io.github.stream29.kodex.openai.SearchCommands
import io.github.stream29.kodex.openai.SearchResponse
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Stable completed local `web.run` interaction.
 *
 * Hosted web search uses [StableWebSearchCall].
 */
@Serializable
@SerialName("web_search_tool_event")
public data class StableWebSearchToolEvent(
    @SerialName("call_id")
    public val callId: String,
    @SerialName("item_id")
    public val itemId: ResponseItemId? = null,
    public val commands: SearchCommands,
    public val result: StableWebSearchResult,
) : StableWorkEvent.CompletedTool {
    override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(
            stableFunctionCall(
                callId = callId,
                itemId = itemId,
                name = "run",
                namespace = "web",
                serializer = SearchCommands.serializer(),
                arguments = commands,
            ),
            result.toFunctionOutput(callId),
        )
}

/** Completed outcome of local web search. */
@Serializable
public sealed interface StableWebSearchResult {
    @Serializable
    @SerialName("success")
    public data class Success(
        public val response: SearchResponse,
    ) : StableWebSearchResult

    @Serializable
    @SerialName("failure")
    public data class Failure(
        public val message: String,
    ) : StableWebSearchResult
}

private fun StableWebSearchResult.toFunctionOutput(
    callId: String,
): ResponseItem.FunctionCallOutput =
    when (this) {
        is StableWebSearchResult.Success ->
            stableTextOutput(
                callId = callId,
                text = response.output,
                success = true,
            )

        is StableWebSearchResult.Failure ->
            stableTextOutput(
                callId = callId,
                text = message,
                success = false,
            )
    }
