package io.github.stream29.codex.lite.llmprovider

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

public object LlmToolChoiceSerializer : KSerializer<LlmToolChoice> {
    override val descriptor: SerialDescriptor = JsonPrimitive.serializer().descriptor

    override fun serialize(encoder: Encoder, value: LlmToolChoice) {
        encoder.encodeString(value.wireName)
    }

    override fun deserialize(decoder: Decoder): LlmToolChoice =
        when (val value = decoder.decodeString()) {
            LlmToolChoice.Auto.wireName -> LlmToolChoice.Auto
            LlmToolChoice.None.wireName -> LlmToolChoice.None
            LlmToolChoice.Required.wireName -> LlmToolChoice.Required
            else -> error("Unknown tool choice: $value")
        }
}

public object LlmFunctionCallOutputPayloadSerializer : KSerializer<LlmFunctionCallOutputPayload> {
    private val contentItemsSerializer = ListSerializer(LlmFunctionCallOutputContentItem.serializer())

    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: LlmFunctionCallOutputPayload) {
        require(encoder is JsonEncoder) {
            "LlmFunctionCallOutputPayload can only be encoded as JSON."
        }
        val element = when (val body = value.body) {
            is LlmFunctionCallOutputBody.Text -> JsonPrimitive(body.text)
            is LlmFunctionCallOutputBody.ContentItems -> {
                encoder.json.encodeToJsonElement(contentItemsSerializer, body.items)
            }
        }
        encoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): LlmFunctionCallOutputPayload {
        require(decoder is JsonDecoder) {
            "LlmFunctionCallOutputPayload can only be decoded as JSON."
        }
        val element = decoder.decodeJsonElement()
        val body = when (element) {
            is JsonArray -> LlmFunctionCallOutputBody.ContentItems(
                decoder.json.decodeFromJsonElement(contentItemsSerializer, element),
            )

            else -> LlmFunctionCallOutputBody.Text(element.jsonPrimitive.content)
        }
        return LlmFunctionCallOutputPayload(body)
    }
}

@Serializable
internal data class OpenAiSubscriptionErrorBody(
    val error: OpenAiSubscriptionError? = null,
    val detail: String? = null,
    val message: String? = null,
    val code: String? = null,
    val type: String? = null,
) {
    fun errorLike(): OpenAiSubscriptionError =
        error ?: OpenAiSubscriptionError(message = message ?: detail, code = code, type = type)
}

@Serializable
internal data class OpenAiSubscriptionError(
    val message: String? = null,
    val code: String? = null,
    val type: String? = null,
)
