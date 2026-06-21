package io.github.stream29.codex.lite.llmprovider

import kotlinx.serialization.KSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

public object LlmProviderJson {
    @OptIn(ExperimentalSerializationApi::class)
    public val default: Json = Json {
        serializersModule = SerializersModule {
            polymorphic(LlmResponseItem::class) {
                defaultDeserializer { LlmResponseItem.Other.serializer() }
            }
            polymorphic(LlmWebSearchAction::class) {
                defaultDeserializer { LlmWebSearchAction.Other.serializer() }
            }
        }
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }
}

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
