package io.github.stream29.codex.lite.llmprovider

import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.Json

internal fun Flow<ServerSentEvent>.toResponsesStreamEvents(json: Json): Flow<ResponsesStreamEvent> =
    mapNotNull { event -> event.toResponsesStreamEvent(json) }

internal fun ServerSentEvent.toResponsesStreamEvent(json: Json): ResponsesStreamEvent? {
    val data = data ?: return null
    if (data == "[DONE]") {
        return null
    }
    return json.decodeFromString<ResponsesStreamEvent>(data)
}
