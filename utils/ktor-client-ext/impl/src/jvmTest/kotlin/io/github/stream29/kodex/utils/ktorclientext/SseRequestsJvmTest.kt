package io.github.stream29.kodex.utils.ktorclientext

import de.infix.testBalloon.framework.core.testSuite
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.SSEClientException
import io.ktor.client.request.get
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.content.OutgoingContent
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.post
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private const val ResponseOne = "first"
private const val ResponseTwo = "second"

val sseRequestsJvmTest by testSuite {
    test("SSE data keeps a long-lived POST alive past the request timeout") {
        withSseServer { baseUrl, state ->
            HttpClient(CIO) {
                install(HttpTimeout) {
                    requestTimeoutMillis = 80
                    socketTimeoutMillis = 500
                }
                install(SSE)
                install(SseCompatibility)
            }.use { client ->
                val events = client.postSseEvents(socketTimeoutMillis = 500) {
                    url("$baseUrl/delayed")
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }.toList()

                assertEquals(listOf(ResponseOne, ResponseTwo), events.map { it.data })
                assertEquals("{}", state.body)
                assertTrue(state.accept.orEmpty().contains(ContentType.Text.EventStream.toString()))
                assertEquals("no-store", state.cacheControl)
            }
        }
    }

    test("SSE comment heartbeats refresh the socket timeout") {
        withSseServer { baseUrl, _ ->
            HttpClient(CIO) {
                install(HttpTimeout) {
                    requestTimeoutMillis = 80
                    socketTimeoutMillis = 100
                }
                install(SSE)
                install(SseCompatibility)
            }.use { client ->
                val events = client.postSseEvents(socketTimeoutMillis = 100) {
                    url("$baseUrl/heartbeat")
                }.toList()

                assertEquals(listOf("alive"), events.map { it.data })
            }
        }
    }

    test("SSE with no network bytes fails with a socket timeout") {
        withSseServer { baseUrl, _ ->
            HttpClient(CIO) {
                install(HttpTimeout) {
                    requestTimeoutMillis = 80
                    socketTimeoutMillis = 100
                }
                install(SSE)
                install(SseCompatibility)
            }.use { client ->
                val failure = assertFailsWith<Throwable> {
                    client.postSseEvents(socketTimeoutMillis = 100) {
                        url("$baseUrl/idle")
                    }.toList()
                }

                assertTrue(
                    failure.causes().any { it is SocketTimeoutException },
                    "Expected socket timeout, got ${failure.causes().toList()}",
                )
                assertTrue(
                    failure.causes().none { it is HttpRequestTimeoutException },
                    "Unexpected request timeout: ${failure.causes().toList()}",
                )
            }
        }
    }

    test("non-SSE requests still use the total request timeout") {
        withSseServer { baseUrl, _ ->
            HttpClient(CIO) {
                install(HttpTimeout) {
                    requestTimeoutMillis = 80
                    socketTimeoutMillis = 500
                }
                install(SseCompatibility)
            }.use { client ->
                assertFailsWith<HttpRequestTimeoutException> {
                    client.get("$baseUrl/slow")
                }
            }
        }
    }

    test("non-success SSE responses expose the response through the SSE exception") {
        withSseServer { baseUrl, _ ->
            HttpClient(CIO) {
                install(SSE)
                install(SseCompatibility)
            }.use { client ->
                val failure = assertFailsWith<SSEClientException> {
                    client.postSseEvents(socketTimeoutMillis = 500) {
                        url("$baseUrl/error")
                    }.toList()
                }

                assertEquals(HttpStatusCode.InternalServerError, failure.response?.status)
            }
        }
    }

    test("SSE compatibility handles a missing event stream content type") {
        withSseServer { baseUrl, _ ->
            HttpClient(CIO) {
                install(SSE)
                install(SseCompatibility)
            }.use { client ->
                val events = client.postSseEvents(socketTimeoutMillis = 500) {
                    url("$baseUrl/missing-content-type")
                }.toList()

                assertEquals(listOf("headerless"), events.map { it.data })
            }
        }
    }

    test("SSE responses require the event stream content type") {
        withSseServer { baseUrl, _ ->
            HttpClient(CIO) {
                install(SSE)
                install(SseCompatibility)
            }.use { client ->
                val failure = assertFailsWith<SSEClientException> {
                    client.postSseEvents(socketTimeoutMillis = 500) {
                        url("$baseUrl/wrong-content-type")
                    }.toList()
                }

                assertEquals(HttpStatusCode.OK, failure.response?.status)
                assertEquals(
                    ContentType.Text.Plain,
                    failure.response?.contentType()?.withoutParameters(),
                )
            }
        }
    }
}

private class SseServerState {
    var body: String? = null
    var accept: String? = null
    var cacheControl: String? = null
}

private suspend fun withSseServer(
    block: suspend (baseUrl: String, state: SseServerState) -> Unit,
) {
    val state = SseServerState()
    val server = embeddedServer(ServerCIO, host = "127.0.0.1", port = 0) {
        routing {
            post("/delayed") {
                state.body = call.receiveText()
                state.accept = call.request.headers[HttpHeaders.Accept]
                state.cacheControl = call.request.headers[HttpHeaders.CacheControl]
                call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                    write("data: $ResponseOne\n\n")
                    flush()
                    delay(150.milliseconds)
                    write("data: $ResponseTwo\n\n")
                    flush()
                }
            }
            post("/heartbeat") {
                call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                    write(": keep-alive\n\n")
                    flush()
                    delay(60.milliseconds)
                    write(": keep-alive\n\n")
                    flush()
                    delay(60.milliseconds)
                    write("data: alive\n\n")
                    flush()
                }
            }
            post("/idle") {
                call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                    delay(250.milliseconds)
                    write("data: late\n\n")
                    flush()
                }
            }
            get("/slow") {
                delay(250.milliseconds)
                call.respondText("late")
            }
            post("/error") {
                call.respondText("error", status = HttpStatusCode.InternalServerError)
            }
            post("/missing-content-type") {
                call.respond(
                    object : OutgoingContent.WriteChannelContent() {
                        override suspend fun writeTo(channel: ByteWriteChannel) {
                            channel.writeStringUtf8("data: headerless\n\n")
                        }
                    },
                )
            }
            post("/wrong-content-type") {
                call.respondText(
                    "data: ignored\n\n",
                    contentType = ContentType.Text.Plain,
                )
            }
        }
    }.start(wait = false)

    try {
        val port = server.engine.resolvedConnectors().first().port
        block("http://127.0.0.1:$port", state)
    } finally {
        server.stop()
    }
}

private fun Throwable.causes(): Sequence<Throwable> =
    generateSequence(this) { it.cause }
