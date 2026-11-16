package io.github.stream29.kodex.utils.ktorclientext

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

public actual fun HttpClient.postSseEventsWithHeaders(
    socketTimeoutMillis: Long,
    onResponseHeaders: suspend (Headers) -> Unit,
    onResponseStatus: suspend (HttpStatusCode) -> Unit,
    configureRequest: HttpRequestBuilder.() -> Unit,
): Flow<ServerSentEvent> =
    channelFlow {
        check(pluginOrNull(SSE) != null) {
            "HttpClient must install SSE before calling postSseEventsWithHeaders."
        }
        sse(
            request = {
                method = HttpMethod.Post
                configureRequest()
                require(socketTimeoutMillis > 0) {
                    "SSE socket timeout must be positive."
                }
                val timeout = getCapabilityOrNull(HttpTimeoutCapability)
                    ?: HttpTimeoutConfig()
                timeout.socketTimeoutMillis = socketTimeoutMillis
                setCapability(HttpTimeoutCapability, timeout)
                expectSuccess = true
                attributes.put(SseCompatibilityRequestAttribute, Unit)
            },
        ) {
            onResponseStatus(call.response.status)
            onResponseHeaders(call.response.headers)
            incoming.collect(::send)
        }
    }
