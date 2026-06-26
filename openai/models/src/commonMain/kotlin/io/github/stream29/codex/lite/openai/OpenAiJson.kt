package io.github.stream29.codex.lite.openai

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive

public object ResponseItemSerializer : KSerializer<ResponseItem> {
    private val knownDelegate = ResponseItem.Known.serializer()
    private val knownTypes = knownDelegate.sealedSubtypeSerialNames()

    override val descriptor: SerialDescriptor = knownDelegate.descriptor

    override fun serialize(encoder: Encoder, value: ResponseItem) {
        if (encoder !is JsonEncoder) {
            require(value is ResponseItem.Known) {
                "ResponseItem.Other can only be encoded as JSON."
            }
            knownDelegate.serialize(encoder, value)
            return
        }

        val element = when (value) {
            is ResponseItem.Known -> encoder.json.encodeToJsonElement(knownDelegate, value)

            ResponseItem.Other -> {
                JsonObject(mapOf("type" to JsonPrimitive("other")))
            }
        }
        encoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): ResponseItem {
        if (decoder !is JsonDecoder) return knownDelegate.deserialize(decoder)
        val element = decoder.decodeJsonElement()
        val knownElement = element.knownTaggedObjectOrNull(knownTypes) ?: return ResponseItem.Other
        return decoder.json.decodeFromJsonElement(knownDelegate, knownElement)
    }
}

public object WebSearchActionSerializer : KSerializer<WebSearchAction> {
    private val knownDelegate = WebSearchAction.Known.serializer()
    private val knownTypes = knownDelegate.sealedSubtypeSerialNames()

    override val descriptor: SerialDescriptor = knownDelegate.descriptor

    override fun serialize(encoder: Encoder, value: WebSearchAction) {
        if (encoder !is JsonEncoder) {
            require(value is WebSearchAction.Known) {
                "WebSearchAction.Other can only be encoded as JSON."
            }
            knownDelegate.serialize(encoder, value)
            return
        }

        val element = when (value) {
            is WebSearchAction.Known -> encoder.json.encodeToJsonElement(knownDelegate, value)

            WebSearchAction.Other -> {
                JsonObject(mapOf("type" to JsonPrimitive("other")))
            }
        }
        encoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): WebSearchAction {
        if (decoder !is JsonDecoder) return knownDelegate.deserialize(decoder)
        val element = decoder.decodeJsonElement()
        val knownElement = element.knownTaggedObjectOrNull(knownTypes) ?: return WebSearchAction.Other
        return decoder.json.decodeFromJsonElement(knownDelegate, knownElement)
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

private fun JsonElement.wireTypeOrNull(): String? =
    (this as? JsonObject)
        ?.get("type")
        ?.jsonPrimitive
        ?.contentOrNull

private fun KSerializer<*>.sealedSubtypeSerialNames(): Set<String> =
    descriptor
        .getElementDescriptor(1)
        .elementNames
        .toSet()

private fun JsonElement.knownTaggedObjectOrNull(knownTypes: Set<String>): JsonElement? =
    when {
        this !is JsonObject -> this
        wireTypeOrNull() == null -> this
        isKnownTaggedObject(knownTypes) -> this
        else -> null
    }

private fun JsonElement.isKnownTaggedObject(knownTypes: Set<String>): Boolean {
    if (this !is JsonObject) return false
    val type = wireTypeOrNull() ?: return false
    return type in knownTypes
}
