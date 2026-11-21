package io.github.stream29.kodex.openai.client

import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.TokenUsageInputDetails
import io.github.stream29.kodex.openai.TokenUsageOutputDetails
import io.github.stream29.kodex.openai.client.contract.ObservedResponseCompletion
import io.github.stream29.kodex.openai.client.contract.ObservedResponseEvent
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.time.ComparableTimeMark

internal data class ReceivedResponseFrame(val receivedAt: ComparableTimeMark, val data: String?)

internal data class DecodedResponseEvent(
    val event: ResponsesStreamEvent,
    val partialTotalTokens: Long? = null,
)

internal fun decodeObservedResponseEvent(data: String): DecodedResponseEvent {
    val payload = OpenAiJsonCodec.parseToJsonElement(data)
    if (payload is JsonObject && (payload["type"] as? JsonPrimitive)?.contentOrNull == "response.completed") {
        val response = payload["response"] as? JsonObject
        val usage = response?.get("usage") as? JsonObject
        if (response != null && usage != null && "output_tokens" !in usage) {
            // Keep the completion and the reported context budget without inventing TokenUsage.outputTokens.
            val partial = OpenAiJsonCodec.decodeFromJsonElement<PartialTokenUsage>(usage)
            val normalized = JsonObject(payload + ("response" to JsonObject(response - "usage")))
            return DecodedResponseEvent(OpenAiJsonCodec.decodeFromJsonElement(normalized), partial.totalTokens)
        }
    }
    return DecodedResponseEvent(OpenAiJsonCodec.decodeFromJsonElement(payload))
}

@Serializable
private data class PartialTokenUsage(
    @SerialName("input_tokens")
    val inputTokens: Long,
    @SerialName("total_tokens")
    val totalTokens: Long,
    @SerialName("input_tokens_details")
    val inputTokensDetails: TokenUsageInputDetails? = null,
    @SerialName("output_tokens_details")
    val outputTokensDetails: TokenUsageOutputDetails? = null,
)

internal class ResponseAttemptObservation {
    private var start: ResponseStart? = null
    private var ended = false

    fun endAttempt() {
        ended = true
        start = null
    }

    fun observe(decoded: DecodedResponseEvent, receivedAt: ComparableTimeMark): ObservedResponseEvent {
        val event = decoded.event
        if (ended) return ObservedResponseEvent(event)
        when (event) {
            is ResponsesStreamEvent.Created -> {
                val responseId = event.response.id
                val previous = start
                when {
                    responseId.isBlank() -> Unit
                    previous == null -> start = ResponseStart(responseId, receivedAt)
                    previous.responseId != responseId -> endAttempt()
                }
            }

            is ResponsesStreamEvent.Completed -> {
                val elapsed = start
                    ?.takeIf { it.responseId == event.response.id }
                    ?.let { receivedAt - it.receivedAt }
                    ?.takeIf { it.isFinite() && it.isPositive() }
                endAttempt()
                return ObservedResponseEvent(
                    event,
                    ObservedResponseCompletion(
                        elapsed = elapsed,
                        reportedOutputTokens = event.response.usage?.outputTokens,
                        reportedTotalTokens = event.response.usage?.totalTokens ?: decoded.partialTotalTokens,
                    ),
                )
            }

            is ResponsesStreamEvent.Failed, is ResponsesStreamEvent.Incomplete -> endAttempt()
            else -> Unit
        }
        return ObservedResponseEvent(event)
    }

    private data class ResponseStart(val responseId: String, val receivedAt: ComparableTimeMark)
}
