package io.github.stream29.kodex.openai.client

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.OpenAiFailureReason
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.client.contract.OpenAiResponseHeaders
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.response.header
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val retry = OpenAiClientRetryConfig(
    maxRetries = 2, baseDelayMillis = 1, maxDelayMillis = 1, randomizationMillis = 0,
)
private const val syntheticId = "synthetic-request-id"
private const val syntheticToken = "synthetic-token"
private const val syntheticBody = "synthetic-body-marker"

val openAiNativeCioTransportTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("CIO policy errors precede ordinary and compaction retries") {
        for (status in listOf(HttpStatusCode.TooManyRequests, HttpStatusCode.ServiceUnavailable)) {
            for (compaction in listOf(false, true)) {
                withLoopback(status, policy = true) { client, endpoint, attempts ->
                    val failure = assertFailsWith<OpenAiModerationBlockedException> {
                        if (compaction) {
                            retryOpenAiStreamingTransportWithBudget(retry) { budget ->
                                client.streamRemoteCompactionEvents(budget, 10_000) {
                                    url(endpoint)
                                    header(HttpHeaders.Authorization, "Bearer $syntheticToken")
                                }.collectRemoteCompactionV2Response()
                            }
                        } else {
                            client.streamResponseEvents(10_000, retry) {
                                url(endpoint)
                                header(HttpHeaders.Authorization, "Bearer $syntheticToken")
                            }.toList()
                        }
                    }
                    assertEquals(status.value, failure.failureDiagnostic?.httpStatus)
                    assertEquals(OpenAiFailureReason.ContentPolicyViolation, failure.failureDiagnostic?.reason)
                    if (compaction) assertEquals(0, failure.retries)
                    assertFalse(failure.toString().contains(syntheticBody))
                    assertFalse(failure.toString().contains(syntheticToken))
                    assertEquals(syntheticId, failure.failureDiagnostic?.requestId)
                    assertFalse(failure.failureDiagnostic.toString().contains(syntheticToken))
                    assertEquals(1, attempts.value)
                }
            }
        }
    }

    test("CIO ordinary transient errors retain the exact HTTP retry budget") {
        for (status in listOf(HttpStatusCode.TooManyRequests, HttpStatusCode.ServiceUnavailable)) {
            withLoopback(status, policy = false) { client, endpoint, attempts ->
                val observed = mutableListOf<OpenAiResponseHeaders>()
                val events = client.streamResponseEvents(10_000, retry, onResponseHeaders = { observed += it }) {
                    url(endpoint)
                    header(HttpHeaders.Authorization, "Bearer $syntheticToken")
                }.toList()
                assertEquals(emptyList(), events)
                assertEquals(status.value, observed.last().failure?.httpStatus)
                assertEquals(3, attempts.value)
            }
        }
    }

    test("CIO final HTTP attempt retains its own structured details and ID") {
        for (compaction in listOf(false, true)) {
            withLoopback(
                HttpStatusCode.TooManyRequests,
                policy = false,
                body = { attempt ->
                    """{"error":{"code":"custom_$attempt","type":"type_$attempt","message":"message_$attempt"},"extra":"$syntheticBody"}"""
                },
                requestId = { attempt -> "$syntheticId-$attempt" },
            ) { client, endpoint, attempts ->
                val diagnostic = if (compaction) {
                    assertFailsWith<OpenAiHttpStreamException> {
                        retryOpenAiStreamingTransportWithBudget(retry) { budget ->
                            client.streamRemoteCompactionEvents(budget, 10_000) { url(endpoint) }
                                .collectRemoteCompactionV2Response()
                        }
                    }.also { assertEquals(2, it.retries) }.failureDiagnostic
                } else {
                    val observed = mutableListOf<OpenAiResponseHeaders>()
                    client.streamResponseEvents(10_000, retry, onResponseHeaders = { observed += it }) {
                        url(endpoint)
                    }.toList()
                    observed.last().failure
                }
                assertEquals(3, attempts.value)
                assertEquals(429, diagnostic?.httpStatus)
                assertEquals(null, diagnostic?.reason)
                assertEquals("$syntheticId-3", diagnostic?.requestId)
                assertEquals("custom_3", diagnostic?.errorCode)
                assertEquals("type_3", diagnostic?.errorType)
                assertEquals("message_3", diagnostic?.errorMessage)
                assertFalse(diagnostic.toString().contains("custom_2"))
                assertFalse(diagnostic.toString().contains(syntheticToken))
                assertFalse(diagnostic.toString().contains(syntheticBody))
            }
        }
    }

    test("CIO truncated and unknown errors do not become moderation blocks") {
        for (body in listOf(
            "{\"error\":{\"code\":\"content_policy_violation\"",
            """{"error":{"code":"$syntheticToken","message":"$syntheticBody","moderation":{"is_blocked":true}}}""",
        )) {
            withLoopback(HttpStatusCode.ServiceUnavailable, policy = false, body = { body }) { client, endpoint, attempts ->
                val observed = mutableListOf<OpenAiResponseHeaders>()
                val events = client.streamResponseEvents(10_000, retry, onResponseHeaders = { observed += it }) {
                    url(endpoint)
                }.toList()
                assertEquals(emptyList(), events)
                assertEquals(3, attempts.value)
                assertEquals(503, observed.last().failure?.httpStatus)
                assertEquals(null, observed.last().failure?.reason)
                if (body.contains(syntheticToken)) {
                    assertEquals(syntheticToken, observed.last().failure?.errorCode)
                    assertEquals(syntheticBody, observed.last().failure?.errorMessage)
                } else {
                    assertEquals(null, observed.last().failure?.errorCode)
                }
            }
        }
    }

    test("CIO oversized unfinished error fails without waiting for EOF") {
        withLoopback(HttpStatusCode.ServiceUnavailable, policy = false, unfinished = true) { client, endpoint, attempts ->
            val failure = withTimeout(5_000) {
                assertFailsWith<OpenAiHttpStreamException> {
                    client.streamResponseEvents(10_000, retry) { url(endpoint) }.toList()
                }
            }
            assertEquals(503, failure.status)
            assertFalse(failure.toString().contains(syntheticBody))
            assertEquals(1, attempts.value)
        }
    }

    test("CIO 200 SSE delivers the first event before the second is produced") {
        val releaseSecond = CompletableDeferred<Unit>()
        val server = embeddedServer(ServerCIO, host = "127.0.0.1", port = 0) {
            routing {
                post("/responses") {
                    call.respondBytesWriter(ContentType.Text.EventStream, HttpStatusCode.OK) {
                        writeStringUtf8(
                            """data: {"type":"response.output_text.delta","item_id":"item","output_index":0,"content_index":0,"delta":"$syntheticBody"}""" + "\n\n",
                        )
                        flush()
                        releaseSecond.await()
                        writeStringUtf8(
                            """data: {"type":"response.output_text.delta","item_id":"item","output_index":0,"content_index":0,"delta":"second"}""" + "\n\n",
                        )
                        flush()
                    }
                }
            }
        }
        try {
            server.startSuspend()
            val endpoint = "http://127.0.0.1:${server.engine.resolvedConnectors().single().port}/responses"
            HttpClient(CIO) {
                configureOpenAiClient(OpenAiClientConfig(baseUrl = endpoint, retry = retry))
            }.use { client ->
                val events = withTimeout(5_000) {
                    client.streamResponseEvents(10_000, retry) { url(endpoint) }
                        .onEach { releaseSecond.complete(Unit) }
                        .toList()
                }
                assertEquals(listOf(syntheticBody, "second"), events.map { (it as ResponsesStreamEvent.OutputTextDelta).delta })
            }
        } finally {
            withContext(NonCancellable) {
                releaseSecond.complete(Unit)
                server.stopSuspend()
            }
        }
    }

    test("CIO cancellation releases an unfinished error connection") {
        coroutineScope {
            val ready = CompletableDeferred<Unit>()
            val continueWriting = CompletableDeferred<Unit>()
            val closed = CompletableDeferred<Unit>()
            val attempts = MutableStateFlow(0)
            val server = embeddedServer(ServerCIO, host = "127.0.0.1", port = 0) {
                routing {
                    post("/responses") {
                        attempts.update { it + 1 }
                        call.respondBytesWriter(ContentType.Application.Json, HttpStatusCode.ServiceUnavailable) {
                            try {
                                writeStringUtf8("{")
                                flush()
                                ready.complete(Unit)
                                continueWriting.await()
                                while (true) {
                                    writeFully(ByteArray(8192) { 'x'.code.toByte() })
                                    flush()
                                    delay(10)
                                }
                            } finally {
                                closed.complete(Unit)
                            }
                        }
                    }
                }
            }
            try {
                server.startSuspend()
                val endpoint = "http://127.0.0.1:${server.engine.resolvedConnectors().single().port}/responses"
                HttpClient(CIO) {
                    configureOpenAiClient(OpenAiClientConfig(baseUrl = endpoint, retry = retry))
                }.use { client ->
                    val request = launch {
                        client.streamResponseEvents(10_000, retry) { url(endpoint) }.toList()
                    }
                    withTimeout(5_000) { ready.await() }
                    request.cancelAndJoin()
                    assertTrue(request.isCancelled)
                    continueWriting.complete(Unit)
                    withTimeout(5_000) { closed.await() }
                    assertEquals(1, attempts.value)
                }
            } finally {
                withContext(NonCancellable) {
                    continueWriting.complete(Unit)
                    server.stopSuspend()
                }
            }
        }
    }
}

private suspend fun withLoopback(
    status: HttpStatusCode,
    policy: Boolean,
    unfinished: Boolean = false,
    body: ((Int) -> String)? = null,
    requestId: (Int) -> String = { syntheticId },
    block: suspend (HttpClient, String, MutableStateFlow<Int>) -> Unit,
) {
    val attempts = MutableStateFlow(0)
    val release = CompletableDeferred<Unit>()
    val server = embeddedServer(ServerCIO, host = "127.0.0.1", port = 0) {
        routing {
            post("/responses") {
                attempts.update { it + 1 }
                call.response.header("x-request-id", requestId(attempts.value))
                if (unfinished) {
                    call.respondBytesWriter(ContentType.Application.Json, status) {
                        writeFully(ByteArray(MaxOpenAiErrorBodyBytes.toInt() + 1) { 'x'.code.toByte() })
                        flush()
                        release.await()
                    }
                } else {
                    val code = if (policy) "content_policy_violation" else "rate_limit_exceeded"
                    call.respondText(
                        body?.invoke(attempts.value) ?: """{"error":{"code":"$code","message":"$syntheticBody"}}""",
                        ContentType.Application.Json,
                        status,
                    )
                }
            }
        }
    }
    try {
        server.startSuspend()
        val endpoint = "http://127.0.0.1:${server.engine.resolvedConnectors().single().port}/responses"
        HttpClient(CIO) {
            configureOpenAiClient(OpenAiClientConfig(baseUrl = endpoint, retry = retry))
        }.use { client ->
            withTimeout(30_000) { block(client, endpoint, attempts) }
        }
    } finally {
        withContext(NonCancellable) {
            release.complete(Unit)
            server.stopSuspend()
        }
    }
}
