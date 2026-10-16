package io.github.stream29.kodex.openai

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Flattens [CodexResponsesRequestKind] into the Codex wire metadata shape.
 */
public object CodexResponsesMetadataSerializer :
    JsonTransformingSerializer<CodexResponsesMetadata>(CodexResponsesMetadata.generatedSerializer()) {
    override fun transformSerialize(element: JsonElement): JsonElement {
        val fields = element.jsonObject.toMutableMap()
        val requestKind = fields.getValue(RequestKindKey).jsonObject
        val wireName = requestKind.getValue(TypeKey)
        fields[RequestKindKey] = wireName
        requestKind[MetadataKey]?.let { fields[CompactionKey] = it }
        if (wireName.jsonPrimitive.content == MemoryWireName) {
            fields.keys.removeAll(TurnIdentityKeys)
        }
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
        if (wireName.jsonPrimitive.content == MemoryWireName) {
            if (ThreadIdKey !in fields) {
                fields[ThreadIdKey] = JsonPrimitive("")
            }
            if (WindowIdKey !in fields) {
                fields[WindowIdKey] = JsonPrimitive("")
            }
        }
        return JsonObject(fields)
    }
}

private const val RequestKindKey: String = "request_kind"
private const val TypeKey: String = "type"
private const val MetadataKey: String = "metadata"
private const val CompactionKey: String = "compaction"
private const val ThreadIdKey: String = "thread_id"
private const val TurnIdKey: String = "turn_id"
private const val WindowIdKey: String = "window_id"
private const val InstallationIdKey: String = "installation_id"
private const val SessionIdKey: String = "session_id"
private const val MemoryWireName: String = "memory"

private val TurnIdentityKeys: Set<String> = setOf(
    InstallationIdKey,
    SessionIdKey,
    ThreadIdKey,
    TurnIdKey,
    WindowIdKey,
)
