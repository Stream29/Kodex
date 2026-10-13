package io.github.stream29.kodex.openai.client

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.ImageEditRequest
import io.github.stream29.kodex.openai.ImageGenerationRequest
import io.github.stream29.kodex.openai.ImageResponse
import io.github.stream29.kodex.openai.ImageUrl
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.OpenAiResult
import io.github.stream29.kodex.openai.OpenAiResponseResult
import io.github.stream29.kodex.openai.OpenAiSubscriptionAuthState
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.client.test.InMemoryOpenAiAuthStore
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

val openAiImageTimeoutHttpTest by testSuite(compartment = { TestCompartment.RealTime }) {
    for (endpoint in listOf("generations", "edits")) {
        test("$endpoint succeeds past the ordinary request and socket timeouts") {
            withImageTimeoutServer(
                OpenAiClientConfig(
                    requestTimeoutMillis = 150, imageRequestTimeoutMillis = 2_000, retry = imageTestRetry,
                ),
                handler = { exchange, _ ->
                    Thread.sleep(400)
                    exchange.reply(imageResponseBody)
                },
            ) { client, attempts ->
                client.requestTestImage(endpoint).assertImageSuccess()
                assertEquals(1, attempts.get())
            }
        }

        test("$endpoint still has a finite image timeout") {
            withImageTimeoutServer(
                OpenAiClientConfig(
                    requestTimeoutMillis = 2_000, imageRequestTimeoutMillis = 150, retry = imageTestRetry,
                ),
                handler = { exchange, _ ->
                    Thread.sleep(600)
                    exchange.reply(imageResponseBody)
                },
            ) { client, attempts ->
                val failure = assertFailsWith<Throwable> { client.requestTestImage(endpoint) }
                failure.assertTransportTimeout()
                assertEquals(1, attempts.get())
            }
        }

        test("$endpoint preserves HTTP retries and the per-request image timeout") {
            withImageTimeoutServer(
                OpenAiClientConfig(
                    requestTimeoutMillis = 150, imageRequestTimeoutMillis = 2_000,
                    retry = imageTestRetry.copy(maxRetries = 1),
                ),
                handler = { exchange, attempt ->
                    if (attempt == 1) {
                        exchange.reply("""{"error":{"message":"retry","type":"test"}}""", status = 503)
                    } else {
                        Thread.sleep(400)
                        exchange.reply(imageResponseBody)
                    }
                },
            ) { client, attempts ->
                client.requestTestImage(endpoint).assertImageSuccess()
                assertEquals(2, attempts.get())
            }
        }
    }

    test("image timeout overrides do not leak into ordinary requests or SSE") {
        withImageTimeoutServer(
            OpenAiClientConfig(
                requestTimeoutMillis = 150,
                imageRequestTimeoutMillis = 2_000,
                sseSocketTimeoutMillis = 2_000,
                retry = imageTestRetry,
            ),
            handler = { exchange, _ ->
                Thread.sleep(400)
                when (exchange.requestURI.path) {
                    "/models" -> exchange.reply("""{"models":[]}""")
                    "/responses" -> exchange.reply(
                        "event: response.completed\ndata: " +
                            """{"type":"response.completed","response":{"id":"fixture"}}""" + "\n\n",
                        contentType = "text/event-stream",
                    )
                    else -> exchange.reply(imageResponseBody)
                }
            },
        ) { client, _ ->
            client.requestTestImage("generations").assertImageSuccess()
            client.requestTestImage("edits").assertImageSuccess()
            assertFailsWith<Throwable> { client.listModels() }.assertTransportTimeout()
            val events = client.createResponse(OpenAiModelId("fixture"), emptyList()).toList()
            assertEquals(1, events.size)
            assertIs<ResponsesStreamEvent.Completed>(events.single())
        }
    }

    test("image transport timeout retries remain bounded") {
        withImageTimeoutServer(
            OpenAiClientConfig(
                requestTimeoutMillis = 2_000, imageRequestTimeoutMillis = 150,
                retry = imageTestRetry.copy(maxRetries = 1),
            ),
            handler = { exchange, _ ->
                Thread.sleep(600)
                exchange.reply(imageResponseBody)
            },
        ) { client, attempts ->
            assertFailsWith<Throwable> { client.requestTestImage("edits") }.assertTransportTimeout()
            assertEquals(2, attempts.get())
        }
    }

    test("cancelling an image request does not retry or prevent subsequent calls") {
        val started = CompletableDeferred<Unit>()
        withImageTimeoutServer(
            OpenAiClientConfig(
                imageRequestTimeoutMillis = 2_000,
                retry = imageTestRetry.copy(maxRetries = 1),
            ),
            handler = { exchange, _ ->
                if (exchange.requestURI.path.startsWith("/images/")) {
                    started.complete(Unit)
                    Thread.sleep(600)
                    exchange.reply(imageResponseBody)
                } else {
                    exchange.reply("""{"models":[]}""")
                }
            },
        ) { client, attempts ->
            coroutineScope {
                val request = launch { client.requestTestImage("edits") }
                withTimeout(5_000) { started.await() }
                withTimeout(5_000) { request.cancelAndJoin() }
            }
            assertEquals(1, attempts.get())
            assertIs<OpenAiResult.Success<*>>(client.listModels())
            assertEquals(1, attempts.get())
        }
    }
}

private val imageTestRetry = OpenAiClientRetryConfig(
    maxRetries = 0, baseDelayMillis = 1, maxDelayMillis = 1, randomizationMillis = 0,
)

private const val imageResponseBody = """{"created":1,"data":[{"b64_json":"Zml4dHVyZQ=="}]}"""

private suspend fun OpenAiClient.requestTestImage(endpoint: String) =
    when (endpoint) {
        "generations" -> generateImage(ImageGenerationRequest("fixture", OpenAiModelId("fixture")))
        "edits" -> editImage(ImageEditRequest(
            listOf(ImageUrl("data:image/png;base64,Zml4dHVyZQ==")), "fixture", OpenAiModelId("fixture"),
        ))
        else -> error("Unknown image endpoint: $endpoint")
    }

private fun OpenAiResponseResult<ImageResponse>.assertImageSuccess() {
    val result = assertIs<OpenAiResult.Success<ImageResponse>>(this)
    assertEquals("Zml4dHVyZQ==", result.value.data.single().b64Json)
}

private fun Throwable.assertTransportTimeout() {
    assertTrue(
        generateSequence(this) { it.cause }.any {
            it is HttpRequestTimeoutException || it is SocketTimeoutException
        },
        "Expected request or socket timeout, got $this",
    )
}

private fun HttpExchange.reply(
    body: String,
    status: Int = 200,
    contentType: String = "application/json",
) {
    val bytes = body.toByteArray()
    responseHeaders.set("Content-Type", contentType)
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

private suspend fun withImageTimeoutServer(
    config: OpenAiClientConfig,
    handler: (HttpExchange, Int) -> Unit,
    block: suspend (OpenAiClient, AtomicInteger) -> Unit,
) {
    val attempts = AtomicInteger()
    val executor = Executors.newFixedThreadPool(2)
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.executor = executor
    server.createContext("/") { exchange ->
        try {
            exchange.requestBody.use { it.readBytes() }
            val attempt = if (exchange.requestURI.path.startsWith("/images/")) {
                attempts.incrementAndGet()
            } else 0
            handler(exchange, attempt)
        } catch (_: IOException) {
            // A timed-out or cancelled client may close before the fixture writes.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            exchange.close()
        }
    }
    server.start()
    try {
        OpenAiClient(
            InMemoryOpenAiAuthStore(OpenAiSubscriptionAuthState("loopback-only", null)),
            config.copy(baseUrl = "http://127.0.0.1:${server.address.port}"),
        ).use { client -> block(client, attempts) }
    } finally {
        server.stop(0)
        executor.shutdownNow()
        check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "Loopback threads did not terminate." }
    }
}
