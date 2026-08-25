package io.github.stream29.kodex.utils.ktorclientext

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpMethod
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/**
 * Opens an SSE response to a POST request.
 *
 * This keeps Ktor's official SSE session inside the returned Flow lifecycle.
 * [SSE] must be installed on this client. [SseCompatibility] can additionally
 * support servers that omit the SSE response content type.
 */
public fun HttpClient.postSseEvents(
    configureRequest: HttpRequestBuilder.() -> Unit,
): Flow<ServerSentEvent> = postSseEventsInternal(socketTimeoutMillis = null, configureRequest)

/**
 * Opens an SSE response to a POST request with a per-request socket timeout.
 *
 * The timeout is an inactivity timeout between network packets. It intentionally
 * does not impose a total request deadline on an SSE request.
 */
public fun HttpClient.postSseEvents(
    socketTimeoutMillis: Long,
    configureRequest: HttpRequestBuilder.() -> Unit,
): Flow<ServerSentEvent> = postSseEventsInternal(socketTimeoutMillis, configureRequest)

private fun HttpClient.postSseEventsInternal(
    socketTimeoutMillis: Long?,
    configureRequest: HttpRequestBuilder.() -> Unit,
): Flow<ServerSentEvent> =
    channelFlow {
        check(pluginOrNull(SSE) != null) {
            "HttpClient must install SSE before calling postSseEvents."
        }
        sse(
            request = {
                method = HttpMethod.Post
                configureRequest()
                if (socketTimeoutMillis != null) {
                    require(socketTimeoutMillis > 0) {
                        "SSE socket timeout must be positive."
                    }
                    val timeout = getCapabilityOrNull(HttpTimeoutCapability)
                        ?: HttpTimeoutConfig()
                    timeout.socketTimeoutMillis = socketTimeoutMillis
                    setCapability(HttpTimeoutCapability, timeout)
                }
                expectSuccess = true
                attributes.put(SseCompatibilityRequestAttribute, Unit)
            },
        ) {
            incoming.collect(::send)
        }
    }
