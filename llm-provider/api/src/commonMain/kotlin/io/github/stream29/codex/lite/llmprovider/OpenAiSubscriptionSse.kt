package io.github.stream29.codex.lite.llmprovider

import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.Json

internal fun Flow<ServerSentEvent>.toLlmResponseStreamEvents(json: Json): Flow<LlmResponseStreamEvent> =
    mapNotNull { event -> event.toLlmResponseStreamEvent(json) }

internal fun ServerSentEvent.toLlmResponseStreamEvent(json: Json): LlmResponseStreamEvent? {
    val data = data ?: return null
    if (data == "[DONE]") {
        return null
    }
    return json.decodeFromString<LlmResponseStreamEvent>(data)
}
