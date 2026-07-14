package io.github.stream29.codex.lite.openai.client.contract

import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.CodexResponsesRequest
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesApiRequest
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.codexRequestWindowId
import io.github.stream29.codex.lite.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Sends a normal Codex turn with the identity and window fields required by the
 * Codex Responses transport.
 */
public suspend fun OpenAiClient.createResponse(request: CodexResponsesRequest): Flow<ResponsesStreamEvent> {
    val windowId = request.checkpoint.codexRequestWindowId(request.threadId)
    val turnMetadata = request.settings.toCodexTurnMetadata(
        threadId = request.threadId,
        windowId = windowId,
        requestKind = "turn",
    )
    return createResponse(
        request = request.toResponsesApiRequest(
            threadId = request.threadId,
            turnMetadata = turnMetadata,
            windowId = windowId,
        ),
        installationId = request.settings.installationId,
        turnMetadata = turnMetadata,
        windowId = windowId,
    )
}

private fun CodexResponsesRequest.toResponsesApiRequest(
    threadId: String,
    turnMetadata: String,
    windowId: String,
): ResponsesApiRequest =
    settings.toResponsesApiRequest(
        input = input,
        threadId = threadId,
        turnMetadata = turnMetadata,
        windowId = windowId,
    )

private fun CodexAgentSettings.toResponsesApiRequest(
    input: List<ResponseItem>,
    threadId: String,
    turnMetadata: String,
    windowId: String,
): ResponsesApiRequest {
    val codexClientMetadata = buildMap {
        installationId?.let { put("x-codex-installation-id", it) }
        sessionId?.let { put("session_id", it) }
        put("thread_id", threadId)
        put("turn_id", turnId)
        put("x-codex-window-id", windowId)
        put("x-codex-turn-metadata", turnMetadata)
    }
    return ResponsesApiRequest(
        model = model,
        input = input,
        instructions = instructions,
        store = store,
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

private fun CodexAgentSettings.toCodexTurnMetadata(
    threadId: String,
    windowId: String,
    requestKind: String,
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
        },
    )
