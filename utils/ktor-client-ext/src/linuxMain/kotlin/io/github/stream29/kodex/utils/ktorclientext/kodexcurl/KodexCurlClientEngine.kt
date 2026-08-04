/*
 * Derived from Ktor's Curl client engine.
 * Copyright 2014-2026 JetBrains s.r.o and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package io.github.stream29.kodex.utils.ktorclientext.kodexcurl

import io.ktor.client.engine.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.utils.*
import io.ktor.http.*
import io.ktor.http.cio.*
import io.ktor.util.date.*
import io.ktor.utils.io.*
import kotlinx.coroutines.job

internal class KodexCurlClientEngine(
    override val config: KodexCurlEngineConfig,
) : HttpClientEngineBase("kodex-curl") {
    override val supportedCapabilities = setOf(HttpTimeoutCapability, WebSocketCapability, SSECapability)

    private val curlProcessor = KodexCurlProcessor(coroutineContext)

    @OptIn(InternalAPI::class)
    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val callContext = callContext()
        val requestTime = GMTDate()
        val responseData = curlProcessor.executeRequest(data.toKodexCurlRequest(config, callContext.job))

        return with(responseData) {
            val headerBytes = ByteReadChannel(headersBytes).apply {
                readLineStrict()
            }
            val rawHeaders = parseHeaders(headerBytes)
            val headers = rawHeaders.toBuilder().apply {
                dropCompressionHeaders(data.method, data.attributes)
            }.build()
            rawHeaders.release()

            val status = HttpStatusCode.fromValue(status)
            val adaptedResponseBody: Any = when {
                data.isUpgradeRequest() && status == HttpStatusCode.SwitchingProtocols -> {
                    val websocket = responseBody as KodexCurlWebSocketResponseBody
                    val webSocketConfig = data.attributes[WEBSOCKETS_KEY]
                    KodexCurlWebSocketSession(
                        websocket = websocket,
                        callContext = callContext,
                        outgoingFramesConfig = webSocketConfig.channelsConfig.outgoing,
                        curlProcessor = curlProcessor,
                    )
                }

                data.isUpgradeRequest() -> ByteReadChannel.Empty
                else -> {
                    val httpResponse = responseBody as KodexCurlHttpResponseBody
                    data.attributes.getOrNull(ResponseAdapterAttributeKey)
                        ?.adapt(data, status, headers, httpResponse.bodyChannel, data.body, callContext)
                        ?: httpResponse.bodyChannel
                }
            }

            HttpResponseData(
                status,
                requestTime,
                headers,
                version.fromKodexCurl(),
                adaptedResponseBody,
                callContext,
            )
        }
    }

    override fun close() {
        super.close()
        curlProcessor.close()
    }
}
