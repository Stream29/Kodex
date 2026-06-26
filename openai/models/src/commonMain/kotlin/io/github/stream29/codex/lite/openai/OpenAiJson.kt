package io.github.stream29.codex.lite.openai

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

public object OpenAiJson {
    @OptIn(ExperimentalSerializationApi::class)
    public val default: Json = Json {
        serializersModule = SerializersModule {
            polymorphic(ResponseItem::class) {
                defaultDeserializer { ResponseItem.Other.serializer() }
            }
            polymorphic(WebSearchAction::class) {
                defaultDeserializer { WebSearchAction.Other.serializer() }
            }
        }
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }
}

public object ToolChoiceSerializer : KSerializer<ToolChoice> {
    override val descriptor: SerialDescriptor = JsonPrimitive.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ToolChoice) {
        encoder.encodeString(value.wireName)
    }

    override fun deserialize(decoder: Decoder): ToolChoice =
        when (val value = decoder.decodeString()) {
            ToolChoice.Auto.wireName -> ToolChoice.Auto
            ToolChoice.None.wireName -> ToolChoice.None
            ToolChoice.Required.wireName -> ToolChoice.Required
            else -> error("Unknown tool choice: $value")
        }
}

public object FunctionCallOutputPayloadSerializer : KSerializer<FunctionCallOutputPayload> {
    private val contentItemsSerializer = ListSerializer(FunctionCallOutputContentItem.serializer())

    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: FunctionCallOutputPayload) {
        require(encoder is JsonEncoder) {
            "FunctionCallOutputPayload can only be encoded as JSON."
        }
        val element = when (val body = value.body) {
            is FunctionCallOutputBody.Text -> JsonPrimitive(body.text)
            is FunctionCallOutputBody.ContentItems -> {
                encoder.json.encodeToJsonElement(contentItemsSerializer, body.items)
            }
        }
        encoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): FunctionCallOutputPayload {
        require(decoder is JsonDecoder) {
            "FunctionCallOutputPayload can only be decoded as JSON."
        }
        val element = decoder.decodeJsonElement()
        val body = when (element) {
            is JsonArray -> FunctionCallOutputBody.ContentItems(
                decoder.json.decodeFromJsonElement(contentItemsSerializer, element),
            )

            else -> FunctionCallOutputBody.Text(element.jsonPrimitive.content)
        }
        return FunctionCallOutputPayload(body)
    }
}
