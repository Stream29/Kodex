package io.github.stream29.codex.lite.utils.ktorclientext

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.sse.SSESession
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.preparePost
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/**
 * Opens an SSE response to a POST request.
 *
 * This owns the streaming [io.ktor.client.statement.HttpStatement] lifecycle,
 * so Ktor never saves the response body before emitting events.
 * [SseCompatibility] must be installed on this client.
 */
public fun HttpClient.postSseEvents(
    configureRequest: HttpRequestBuilder.() -> Unit,
): Flow<ServerSentEvent> =
    channelFlow {
        check(pluginOrNull(SseCompatibility) != null) {
            "HttpClient must install SseCompatibility before calling postSseEvents."
        }
        preparePost {
            configureRequest()
            expectSuccess = true
            headers[HttpHeaders.Accept] = ContentType.Text.EventStream.toString()
            headers[HttpHeaders.CacheControl] = "no-store"
            attributes.put(SseCompatibilityRequestAttribute, Unit)
        }.body<SSESession, Unit> { session ->
            session.incoming.collect(::send)
        }
    }
