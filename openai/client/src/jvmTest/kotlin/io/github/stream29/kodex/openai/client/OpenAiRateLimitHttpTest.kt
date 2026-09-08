package io.github.stream29.kodex.openai.client

import com.sun.net.httpserver.HttpServer
import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.OpenAiSubscriptionAuthState
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.ResponsesApiRequest
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.client.test.InMemoryOpenAiAuthStore
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.coroutines.flow.toList
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFails

val openAiRateLimitHttpTest by testSuite(compartment = { TestCompartment.RealTime }) {
    for (withMetadata in listOf(false, true)) {
        test("responses retries HTTP 429 before succeeding with metadata=$withMetadata") {
            withRateLimitServer(listOf(429, 429, 200)) { client, attempts ->
                val events = if (withMetadata) {
                    client.createResponse(rateLimitRequest(), null, "{}", "window")
                } else {
                    client.createResponse(rateLimitRequest())
                }.toList()

                assertEquals(listOf(ResponsesStreamEvent.Completed(Response(id = "recovered"))), events)
                assertEquals(3, attempts.get())
            }
        }
    }

    test("responses hands HTTP 429 back to the agent retry loop after HTTP retries") {
        withRateLimitServer(listOf(429)) { client, attempts ->
            assertEquals(emptyList(), client.createResponse(rateLimitRequest()).toList())
            assertEquals(3, attempts.get())
        }
    }

    test("responses can recover on the next agent attempt after HTTP retries") {
        withRateLimitServer(listOf(429, 429, 429, 200)) { client, attempts ->
            assertEquals(emptyList(), client.createResponse(rateLimitRequest()).toList())
            assertEquals(
                listOf(ResponsesStreamEvent.Completed(Response(id = "recovered"))),
                client.createResponse(rateLimitRequest()).toList(),
            )
            assertEquals(4, attempts.get())
        }
    }

    test("responses respects disabled rate limit retries") {
        withRateLimitServer(listOf(429), rateLimitRetry.copy(retryRateLimited = false)) { client, attempts ->
            assertFails { client.createResponse(rateLimitRequest()).toList() }
            assertEquals(1, attempts.get())
        }
    }

    for (status in listOf(400, 401, 403, 404)) {
        test("responses does not retry HTTP $status") {
            withRateLimitServer(listOf(status)) { client, attempts ->
                assertFails { client.createResponse(rateLimitRequest()).toList() }
                assertEquals(1, attempts.get())
            }
        }
    }
}

private val rateLimitRetry = OpenAiClientRetryConfig(
    maxRetries = 2,
    baseDelayMillis = 1,
    maxDelayMillis = 1,
    randomizationMillis = 0,
)

private fun rateLimitRequest() = ResponsesApiRequest(OpenAiModelId("test"), emptyList())

private suspend fun withRateLimitServer(
    statuses: List<Int>,
    retry: OpenAiClientRetryConfig = rateLimitRetry,
    block: suspend (OpenAiClient, AtomicInteger) -> Unit,
) {
    val attempts = AtomicInteger()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/responses") { exchange ->
        try {
            exchange.requestBody.use { it.readBytes() }
            val status = statuses[attempts.getAndIncrement().coerceAtMost(statuses.lastIndex)]
            val body = if (status == 200) {
                val event = OpenAiJsonCodec.encodeToString(
                    ResponsesStreamEvent.serializer(),
                    ResponsesStreamEvent.Completed(Response(id = "recovered")),
                )
                "event: response.completed\ndata: $event\n\n"
            } else {
                """{"error":{"message":"HTTP $status","type":"test_error"}}"""
            }.toByteArray()
            exchange.responseHeaders.set(
                "Content-Type",
                if (status == 200) "text/event-stream" else "application/json",
            )
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        } finally {
            exchange.close()
        }
    }
    server.start()
    try {
        OpenAiClient(
            InMemoryOpenAiAuthStore(OpenAiSubscriptionAuthState(accessToken = "loopback-only", accountId = null)),
            OpenAiClientConfig(baseUrl = "http://127.0.0.1:${server.address.port}", retry = retry),
        ).use { client -> block(client, attempts) }
    } finally {
        server.stop(0)
    }
}
