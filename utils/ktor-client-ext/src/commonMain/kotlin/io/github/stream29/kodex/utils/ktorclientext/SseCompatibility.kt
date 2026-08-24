package io.github.stream29.kodex.utils.ktorclientext

import io.ktor.client.HttpClient
import io.ktor.client.plugins.api.ClientHook
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.sse.DefaultClientSSESession
import io.ktor.client.plugins.sse.SSEBufferPolicy
import io.ktor.client.plugins.sse.SSECapability
import io.ktor.client.plugins.sse.SSEClientContent
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.client.request.ResponseAdapter
import io.ktor.client.request.ResponseAdapterAttributeKey
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.content.OutgoingContent
import io.ktor.util.AttributeKey
import io.ktor.util.pipeline.PipelinePhase
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds

@OptIn(InternalAPI::class)
public val SseCompatibility: ClientPlugin<Unit> = createClientPlugin("SseCompatibility") {
    on(AfterRender) { request, content ->
        if (request.attributes.contains(SseCompatibilityRequestAttribute)) {
            request.setCapability(SSECapability, Unit)
            request.attributes.put(ResponseAdapterAttributeKey, SseResponseAdapter)
            request.attributes.put(SseClientForReconnectionAttribute, client)
            val contentType = content.contentType
            if (contentType != null) {
                request.contentType(contentType)
            }
            SSEClientContent(
                reconnectionTime = 3.seconds,
                showCommentEvents = false,
                showRetryEvents = false,
                maxReconnectionAttempts = 0,
                bufferPolicy = SSEBufferPolicy.Off,
                callContext = currentCoroutineContext(),
                initialRequest = request,
                requestBody = content,
            )
        } else {
            content
        }
    }
}

internal val SseCompatibilityRequestAttribute: AttributeKey<Unit> =
    AttributeKey("SseCompatibilityRequest")

@OptIn(InternalAPI::class)
private object SseResponseAdapter : ResponseAdapter {
    /**
     * @return Nullable because Ktor uses `null` to mean this adapter does not
     * handle the response.
     */
    @Suppress("DEPRECATION")
    override fun adapt(
        data: io.ktor.client.request.HttpRequestData,
        status: HttpStatusCode,
        headers: Headers,
        responseBody: ByteReadChannel,
        outgoingContent: OutgoingContent,
        callContext: CoroutineContext,
    ): Any? =
        if (status == HttpStatusCode.OK || status == HttpStatusCode.NoContent) {
            val sseContent = outgoingContent as? SSEClientContent
                ?: error("SseCompatibility response received a non-SSE request body")
            val sessionContent = SSEClientContent(
                reconnectionTime = sseContent.reconnectionTime,
                showCommentEvents = sseContent.showCommentEvents,
                showRetryEvents = sseContent.showRetryEvents,
                maxReconnectionAttempts = sseContent.maxReconnectionAttempts,
                bufferPolicy = sseContent.bufferPolicy,
                callContext = callContext,
                initialRequest = sseContent.initialRequest,
                requestBody = sseContent,
            )
            DefaultClientSSESession(
                content = sessionContent,
                input = responseBody,
            )
        } else {
            null
        }
}

private val SseClientForReconnectionAttribute: AttributeKey<HttpClient> =
    AttributeKey("SSEClientForReconnection")

private object AfterRender : ClientHook<suspend (HttpRequestBuilder, OutgoingContent) -> OutgoingContent> {
    override fun install(
        client: HttpClient,
        handler: suspend (HttpRequestBuilder, OutgoingContent) -> OutgoingContent,
    ) {
        val phase = PipelinePhase("SseCompatibilityAfterRender")
        client.requestPipeline.insertPhaseAfter(HttpRequestPipeline.Render, phase)
        client.requestPipeline.intercept(phase) { content ->
            if (content !is OutgoingContent) {
                return@intercept
            }
            proceedWith(handler(context, content))
        }
    }
}
