package io.github.stream29.codex.lite.openai.client

import io.ktor.client.HttpClient
import io.ktor.client.plugins.api.SendingRequest
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.sse.DefaultClientSSESession
import io.ktor.client.plugins.sse.SSEBufferPolicy
import io.ktor.client.plugins.sse.SSEClientContent
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.ResponseAdapter
import io.ktor.client.request.ResponseAdapterAttributeKey
import io.ktor.client.request.takeFrom
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.util.AttributeKey
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds

@OptIn(InternalAPI::class)
internal val CodexSseCompatibility = createClientPlugin("CodexSseCompatibility") {
    on(SendingRequest) { request, _ ->
        if (request.attributes.getOrNull(CodexSseCompatibilityRequestAttribute) == true) {
            request.attributes.put(ResponseAdapterAttributeKey, CodexSseResponseAdapter(client))
        }
    }
}

@OptIn(InternalAPI::class)
internal fun HttpRequestBuilder.acceptCodexSseWithoutContentType() {
    attributes.put(CodexSseCompatibilityRequestAttribute, true)
}

private val CodexSseCompatibilityRequestAttribute: AttributeKey<Boolean> =
    AttributeKey("CodexSseCompatibilityRequest")

@OptIn(InternalAPI::class)
private class CodexSseResponseAdapter(
    private val client: HttpClient,
) : ResponseAdapter {
    /**
     * @return Nullable because Ktor uses `null` to mean this adapter does not
     * handle the response.
     */
    @Suppress("DEPRECATION")
    override fun adapt(
        data: HttpRequestData,
        status: HttpStatusCode,
        headers: Headers,
        responseBody: ByteReadChannel,
        outgoingContent: OutgoingContent,
        callContext: CoroutineContext,
    ): Any? =
        if (status == HttpStatusCode.OK || status == HttpStatusCode.NoContent) {
            DefaultClientSSESession(
                content = SSEClientContent(
                    reconnectionTime = 3.seconds,
                    showCommentEvents = false,
                    showRetryEvents = false,
                    maxReconnectionAttempts = 0,
                    bufferPolicy = SSEBufferPolicy.Off,
                    callContext = callContext,
                    initialRequest = initialRequest(data),
                    requestBody = outgoingContent,
                ),
                input = responseBody,
            )
        } else {
            null
        }

    private fun initialRequest(data: HttpRequestData): HttpRequestBuilder =
        HttpRequestBuilder()
            .takeFrom(data)
            .apply {
                attributes.put(SseClientForReconnectionAttribute, client)
            }
}

private val SseClientForReconnectionAttribute: AttributeKey<HttpClient> =
    AttributeKey("SSEClientForReconnection")
