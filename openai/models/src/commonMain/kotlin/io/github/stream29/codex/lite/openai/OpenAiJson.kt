package io.github.stream29.codex.lite.openai

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive

public object ResponseItemSerializer : KSerializer<ResponseItem> {
    private val knownDelegate = ResponseItem.Known.serializer()
    private val knownTypes = knownDelegate.sealedSubtypeSerialNames() - toolSearchInternalTypes

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
            is ResponseItem.ClientToolSearchCall -> encoder.json.encodeToolSearchItem(
                value = value,
                serializer = ResponseItem.ClientToolSearchCall.serializer(),
                type = toolSearchCallType,
                execution = clientToolSearchExecution,
            )

            is ResponseItem.ServerToolSearchCall -> encoder.json.encodeToolSearchItem(
                value = value,
                serializer = ResponseItem.ServerToolSearchCall.serializer(),
                type = toolSearchCallType,
                execution = serverToolSearchExecution,
                callId = JsonNull,
            )

            is ResponseItem.ClientToolSearchOutput -> encoder.json.encodeToolSearchItem(
                value = value,
                serializer = ResponseItem.ClientToolSearchOutput.serializer(),
                type = toolSearchOutputType,
                execution = clientToolSearchExecution,
            )

            is ResponseItem.ServerToolSearchOutput -> encoder.json.encodeToolSearchItem(
                value = value,
                serializer = ResponseItem.ServerToolSearchOutput.serializer(),
                type = toolSearchOutputType,
                execution = serverToolSearchExecution,
                callId = JsonNull,
            )

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
        if (element is JsonObject) {
            when (element.wireTypeOrNull()) {
                toolSearchCallType -> return element.decodeToolSearchCall(decoder.json)
                toolSearchOutputType -> return element.decodeToolSearchOutput(decoder.json)
            }
        }
        val knownElement = element.knownTaggedObjectOrNull(knownTypes) ?: return ResponseItem.Other
        return decoder.json.decodeFromJsonElement(knownDelegate, knownElement)
    }
}

public object ResponsesStreamEventSerializer : KSerializer<ResponsesStreamEvent> {
    private val knownDelegate = ResponsesStreamEvent.Known.serializer()
    private val knownTypes = knownDelegate.sealedSubtypeSerialNames()

    override val descriptor: SerialDescriptor = knownDelegate.descriptor

    override fun serialize(encoder: Encoder, value: ResponsesStreamEvent) {
        if (encoder !is JsonEncoder) {
            require(value is ResponsesStreamEvent.Known) {
                "ResponsesStreamEvent.Other can only be encoded as JSON."
            }
            knownDelegate.serialize(encoder, value)
            return
        }

        val element = when (value) {
            is ResponsesStreamEvent.Known -> encoder.json.encodeToJsonElement(knownDelegate, value)
            is ResponsesStreamEvent.Other -> value.payload
        }
        encoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): ResponsesStreamEvent {
        if (decoder !is JsonDecoder) return knownDelegate.deserialize(decoder)
        val element = decoder.decodeJsonElement()
        if (element !is JsonObject) return ResponsesStreamEvent.Other(element)
        val knownElement = element.knownTaggedObjectOrNull(knownTypes) ?: return ResponsesStreamEvent.Other(element)
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

private fun JsonObject.decodeToolSearchCall(json: Json): ResponseItem =
    when (this["execution"]?.jsonPrimitive?.contentOrNull) {
        clientToolSearchExecution -> {
            if (this["call_id"]?.jsonPrimitive?.contentOrNull == null) {
                ResponseItem.Other
            } else {
                json.decodeFromJsonElement(
                    ResponseItem.ClientToolSearchCall.serializer(),
                    withoutKeys("type", "execution"),
                )
            }
        }

        serverToolSearchExecution -> {
            if (this["call_id"]?.jsonPrimitive?.contentOrNull != null) {
                ResponseItem.Other
            } else {
                json.decodeFromJsonElement(
                    ResponseItem.ServerToolSearchCall.serializer(),
                    withoutKeys("type", "execution", "call_id"),
                )
            }
        }

        else -> ResponseItem.Other
    }

private fun JsonObject.decodeToolSearchOutput(json: Json): ResponseItem =
    when (this["execution"]?.jsonPrimitive?.contentOrNull) {
        clientToolSearchExecution -> {
            if (this["call_id"]?.jsonPrimitive?.contentOrNull == null) {
                ResponseItem.Other
            } else {
                json.decodeFromJsonElement(
                    ResponseItem.ClientToolSearchOutput.serializer(),
                    withoutKeys("type", "execution"),
                )
            }
        }

        serverToolSearchExecution -> {
            if (this["call_id"]?.jsonPrimitive?.contentOrNull != null) {
                ResponseItem.Other
            } else {
                json.decodeFromJsonElement(
                    ResponseItem.ServerToolSearchOutput.serializer(),
                    withoutKeys("type", "execution", "call_id"),
                )
            }
        }

        else -> ResponseItem.Other
    }

private fun JsonObject.withoutKeys(vararg names: String): JsonObject =
    JsonObject(filterKeys { key -> key !in names })

private fun <T> Json.encodeToolSearchItem(
    value: T,
    serializer: KSerializer<T>,
    type: String,
    execution: String,
    callId: JsonElement? = null,
): JsonObject {
    val fields = encodeToJsonElement(serializer, value) as JsonObject
    return JsonObject(
        buildMap {
            put("type", JsonPrimitive(type))
            putAll(fields)
            if (callId != null) {
                put("call_id", callId)
            }
            put("execution", JsonPrimitive(execution))
        },
    )
}

private const val toolSearchCallType: String = "tool_search_call"
private const val toolSearchOutputType: String = "tool_search_output"
private const val clientToolSearchExecution: String = "client"
private const val serverToolSearchExecution: String = "server"

private val toolSearchInternalTypes: Set<String> = setOf(
    "client_tool_search_call",
    "server_tool_search_call",
    "client_tool_search_output",
    "server_tool_search_output",
)

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
