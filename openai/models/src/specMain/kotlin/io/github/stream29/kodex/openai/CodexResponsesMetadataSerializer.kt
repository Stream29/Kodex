package io.github.stream29.kodex.openai

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.jsonObject

/**
 * Flattens [CodexResponsesRequestKind] into the Codex wire metadata shape.
 */
public object CodexResponsesMetadataSerializer :
    JsonTransformingSerializer<CodexResponsesMetadata>(CodexResponsesMetadata.generatedSerializer()) {
    override fun transformSerialize(element: JsonElement): JsonElement {
        val fields = element.jsonObject.toMutableMap()
        val requestKind = fields.getValue(RequestKindKey).jsonObject
        fields[RequestKindKey] = requestKind.getValue(TypeKey)
        requestKind[MetadataKey]?.let { fields[CompactionKey] = it }
        return JsonObject(fields)
    }

    override fun transformDeserialize(element: JsonElement): JsonElement {
        val fields = element.jsonObject.toMutableMap()
        val wireName = fields.getValue(RequestKindKey)
        fields[RequestKindKey] = JsonObject(
            buildMap {
                put(TypeKey, wireName)
                fields.remove(CompactionKey)?.let { put(MetadataKey, it) }
            },
        )
        return JsonObject(fields)
    }
}

private const val RequestKindKey: String = "request_kind"
private const val TypeKey: String = "type"
private const val MetadataKey: String = "metadata"
private const val CompactionKey: String = "compaction"
