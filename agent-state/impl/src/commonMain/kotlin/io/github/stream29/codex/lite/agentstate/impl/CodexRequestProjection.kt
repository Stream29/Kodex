package io.github.stream29.codex.lite.agentstate.impl

import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesApiRequest
import io.github.stream29.codex.lite.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun CodexAgentSettings.toResponsesApiRequest(
    input: List<ResponseItem>,
    threadId: String,
    turnMetadata: String,
    windowId: String,
): ResponsesApiRequest {
    val codexClientMetadata = buildMap {
        installationId?.let { put(CodexInstallationIdMetadataKey, it) }
        sessionId?.let { put("session_id", it) }
        put("thread_id", threadId)
        put("turn_id", turnId)
        put(CodexWindowIdMetadataKey, windowId)
        put(CodexTurnMetadataMetadataKey, turnMetadata)
    }
    return ResponsesApiRequest(
        model = model,
        input = input,
        instructions = instructions,
        store = false,
        previousResponseId = previousResponseId,
        tools = tools,
        toolChoice = toolChoice,
        parallelToolCalls = parallelToolCalls,
        reasoning = reasoning,
        include = include,
        serviceTier = serviceTier,
        promptCacheKey = promptCacheKey,
        text = text,
        clientMetadata = clientMetadata + codexClientMetadata,
    )
}

internal fun CodexAgentSettings.toCodexTurnMetadata(
    threadId: String,
    windowId: String,
    requestKind: String,
    compaction: JsonObject? = null,
): String =
    OpenAiJsonCodec.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            installationId?.let { put("installation_id", it) }
            sessionId?.let { put("session_id", it) }
            put("thread_id", threadId)
            put("turn_id", turnId)
            put("window_id", windowId)
            put("request_kind", requestKind)
            compaction?.let { put("compaction", it) }
        },
    )

private const val CodexInstallationIdMetadataKey: String = "x-codex-installation-id"
private const val CodexTurnMetadataMetadataKey: String = "x-codex-turn-metadata"
private const val CodexWindowIdMetadataKey: String = "x-codex-window-id"
