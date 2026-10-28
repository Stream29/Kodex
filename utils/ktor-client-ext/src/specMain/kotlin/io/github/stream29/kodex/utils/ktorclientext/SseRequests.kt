package io.github.stream29.kodex.utils.ktorclientext

import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.flow.Flow

/**
 * Opens an SSE response to a POST request.
 *
 * This keeps Ktor's official SSE session inside the returned Flow lifecycle.
 * [SSE] must be installed on this client. [SseCompatibility] can additionally
 * support servers that omit the SSE response content type.
 */
public expect fun HttpClient.postSseEvents(
    configureRequest: HttpRequestBuilder.() -> Unit,
): Flow<ServerSentEvent>

/**
 * Opens an SSE response to a POST request with a per-request socket timeout.
 *
 * The timeout is an inactivity timeout between network packets. It intentionally
 * does not impose a total request deadline on an SSE request.
 */
public expect fun HttpClient.postSseEvents(
    socketTimeoutMillis: Long,
    configureRequest: HttpRequestBuilder.() -> Unit,
): Flow<ServerSentEvent>

public expect fun HttpClient.postSseEventsWithHeaders(
    socketTimeoutMillis: Long,
    onResponseHeaders: suspend (Headers) -> Unit,
    onResponseStatus: suspend (HttpStatusCode) -> Unit = {},
    configureRequest: HttpRequestBuilder.() -> Unit,
): Flow<ServerSentEvent>
