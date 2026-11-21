package io.github.stream29.kodex.utils.ktorclientext

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.flow.Flow

public expect fun HttpClient.postSseEventsWithHeaders(
    socketTimeoutMillis: Long,
    onResponseHeaders: suspend (Headers) -> Unit,
    onResponseStatus: suspend (HttpStatusCode) -> Unit = {},
    configureRequest: HttpRequestBuilder.() -> Unit,
): Flow<ServerSentEvent>

/** Transforms each frame in the SSE reader, before it can wait in the downstream channel. */
public expect fun <T> HttpClient.postSseEventsWithHeaders(
    socketTimeoutMillis: Long,
    onResponseHeaders: suspend (Headers) -> Unit,
    onResponseStatus: suspend (HttpStatusCode) -> Unit = {},
    transformOnRead: (ServerSentEvent) -> T,
    configureRequest: HttpRequestBuilder.() -> Unit,
): Flow<T>
