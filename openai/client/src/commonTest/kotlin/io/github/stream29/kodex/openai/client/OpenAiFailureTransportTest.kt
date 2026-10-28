package io.github.stream29.kodex.openai.client

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.OpenAiFailureReason
import io.github.stream29.kodex.openai.OpenAiFailureTermination
import io.github.stream29.kodex.openai.client.contract.OpenAiResponseHeaders
import io.github.stream29.kodex.utils.ktorclientext.SseCompatibility
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.SSECapability
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.ResponseAdapterAttributeKey
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.headers
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

val openAiFailureTransportTest by testSuite {
    test("simulated rate limit keeps HTTP status and the empty retryable flow") {
        val observed = mutableListOf<OpenAiResponseHeaders>()
        var attempts = 0
        HttpClient(SseMockEngine {
            attempts++
            respond(
                "Bearer access-token",
                HttpStatusCode.TooManyRequests,
                headers { append("x-request-id", "123e4567-e89b-12d3-a456-426614174000") },
            )
        }) {
            install(SSE)
            install(SseCompatibility)
        }.use { client ->
            val events = client.streamResponseEvents(
                socketTimeoutMillis = 1000,
                retry = OpenAiClientRetryConfig(),
                onResponseHeaders = { observed += it },
            ) { url("https://example.test/responses") }.toList()
            assertEquals(emptyList(), events)
        }
        val diagnostic = requireNotNull(observed.last().failure)
        assertEquals(OpenAiFailureTermination.HttpError, diagnostic.termination)
        assertEquals(429, diagnostic.httpStatus)
        assertEquals("123e4567-e89b-12d3-a456-426614174000", diagnostic.requestId)
        assertFalse(diagnostic.toString().contains("access-token"))
        assertEquals(1, attempts)
    }

    test("simulated SSE moderation block retains structured provider text") {
        val body = """data: {"type":"response.failed","response":{"id":"resp_synthetic","error":{"code":"content_policy_violation","message":"Bearer access-token private prompt"}}}

"""
        var attempts = 0
        HttpClient(SseMockEngine {
            attempts++
            respond(body, HttpStatusCode.OK, headers {
                append(HttpHeaders.ContentType, "text/event-stream")
                append("x-request-id", "nonstandard/request:id")
            })
        }) {
            install(SSE)
            install(SseCompatibility)
        }.use { client ->
            val failure = assertFailsWith<OpenAiModerationBlockedException> {
                client.streamResponseEvents(1000, OpenAiClientRetryConfig()) {
                    url("https://example.test/responses")
                }.toList()
            }
            assertEquals(OpenAiFailureReason.ContentPolicyViolation, failure.failureDiagnostic?.reason)
            assertEquals(200, failure.failureDiagnostic?.httpStatus)
            assertFalse(failure.toString().contains("access-token"))
            assertFalse(failure.toString().contains("private prompt"))
            assertEquals("resp_synthetic", failure.failureDiagnostic?.responseId)
            assertEquals("nonstandard/request:id", failure.failureDiagnostic?.requestId)
            assertEquals("Bearer access-token private prompt", failure.failureDiagnostic?.errorMessage)
            assertEquals("content_policy_violation", failure.failureDiagnostic?.errorCode)
        }
        assertEquals(1, attempts)
    }

    test("simulated structured HTTP policy rejection is not retryable") {
        var attempts = 0
        HttpClient(SseMockEngine {
            attempts++
            respond(
                """{"error":{"code":"content_policy_violation","message":"private prompt"}}""",
                HttpStatusCode.BadRequest,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }) {
            install(SSE)
            install(SseCompatibility)
        }.use { client ->
            val failure = assertFailsWith<OpenAiModerationBlockedException> {
                client.streamResponseEvents(1000, OpenAiClientRetryConfig()) {
                    url("https://example.test/responses")
                }.toList()
            }
            assertEquals(OpenAiFailureReason.ContentPolicyViolation, failure.failureDiagnostic?.reason)
            assertEquals(400, failure.failureDiagnostic?.httpStatus)
            assertEquals("content_policy_violation", failure.failureDiagnostic?.errorCode)
            assertEquals("private prompt", failure.failureDiagnostic?.errorMessage)
            assertFalse(failure.toString().contains("private prompt"))
        }
        assertEquals(1, attempts)
    }

    test("structured policy rejection takes precedence over retryable HTTP status in stream handlers") {
        for (status in listOf(HttpStatusCode.TooManyRequests, HttpStatusCode.ServiceUnavailable)) {
            var attempts = 0
            HttpClient(SseMockEngine {
                attempts++
                respond(
                    """{"error":{"code":"content_policy_violation","message":"private prompt"}}""",
                    status,
                    headers {
                        append(HttpHeaders.ContentType, "application/json")
                        append("x-request-id", "req_policy_ordinary")
                    },
                )
            }) {
                installOpenAiRetry(
                    OpenAiClientRetryConfig(maxRetries = 2, baseDelayMillis = 1, maxDelayMillis = 1, randomizationMillis = 0),
                )
                install(SSE)
                install(SseCompatibility)
            }.use { client ->
                val failure = assertFailsWith<OpenAiModerationBlockedException> {
                    client.streamResponseEvents(1000, OpenAiClientRetryConfig()) {
                        url("https://example.test/responses")
                    }.toList()
                }
                assertEquals(status.value, failure.failureDiagnostic?.httpStatus)
                assertEquals("private prompt", failure.failureDiagnostic?.errorMessage)
                assertFalse(failure.toString().contains("private prompt"))
                assertEquals("req_policy_ordinary", failure.failureDiagnostic?.requestId)
                assertFalse(failure.failureDiagnostic.toString().contains("access-token"))
            }
            assertEquals(1, attempts)
        }
    }

    test("compaction policy rejection takes precedence over retryable HTTP status") {
        for (status in listOf(HttpStatusCode.TooManyRequests, HttpStatusCode.ServiceUnavailable)) {
            var attempts = 0
            HttpClient(SseMockEngine {
                attempts++
                respond(
                    """{"error":{"code":"content_policy_violation","message":"private prompt"}}""",
                    status,
                    headers {
                        append(HttpHeaders.ContentType, "application/json")
                        append("x-request-id", "req_policy_compaction")
                    },
                )
            }) {
                installOpenAiRetry(
                    OpenAiClientRetryConfig(maxRetries = 2, baseDelayMillis = 1, maxDelayMillis = 1, randomizationMillis = 0),
                )
                install(SSE)
                install(SseCompatibility)
            }.use { client ->
                val failure = assertFailsWith<OpenAiModerationBlockedException> {
                    retryOpenAiStreamingTransportWithBudget(
                        OpenAiClientRetryConfig(maxRetries = 2, baseDelayMillis = 1, maxDelayMillis = 1, randomizationMillis = 0),
                    ) { budget ->
                        client.streamRemoteCompactionEvents(budget, 1000) {
                            url("https://example.test/responses")
                        }.collectRemoteCompactionV2Response()
                    }
                }
                assertEquals(status.value, failure.failureDiagnostic?.httpStatus)
                assertEquals("private prompt", failure.failureDiagnostic?.errorMessage)
                assertEquals(0, failure.retries)
                assertEquals("req_policy_compaction", failure.failureDiagnostic?.requestId)
                assertFalse(failure.failureDiagnostic.toString().contains("access-token"))
            }
            assertEquals(1, attempts)
        }
    }

    test("ordinary HTTP rate limit retains the production retry budget") {
        var attempts = 0
        val retry = OpenAiClientRetryConfig(
            maxRetries = 2, baseDelayMillis = 1, maxDelayMillis = 1, randomizationMillis = 0,
        )
        HttpClient(SseMockEngine {
            attempts++
            respond("""{"error":{"code":"rate_limit_exceeded","message":"Bearer access-token"}}""", HttpStatusCode.TooManyRequests,
                headers { append("x-request-id", "req_http_$attempts") })
        }) {
            installOpenAiRetry(retry)
            install(SSE)
            install(SseCompatibility)
        }.use { client ->
            val observed = mutableListOf<OpenAiResponseHeaders>()
            val events = client.streamResponseEvents(1000, retry, onResponseHeaders = { observed += it }) {
                url("https://example.test/responses")
            }.toList()
            assertEquals(emptyList(), events)
            assertEquals(429, observed.last().failure?.httpStatus)
            assertEquals("req_http_3", observed.last().failure?.requestId)
            assertEquals("Bearer access-token", observed.last().failure?.errorMessage)
        }
        assertEquals(3, attempts)
    }

    test("missing or empty final HTTP identifiers never inherit an earlier attempt") {
        for (empty in listOf(false, true)) {
            for (compaction in listOf(false, true)) {
                var attempts = 0
                val retry = OpenAiClientRetryConfig(maxRetries = 1, baseDelayMillis = 1, maxDelayMillis = 1, randomizationMillis = 0)
                HttpClient(SseMockEngine {
                    attempts++
                    val body = if (attempts == 1) """{"error":{"code":"previous","type":"previous","message":"previous"}}"""
                        else """{"error":{"code":null,"type":"","message":null}}"""
                    respond(body, HttpStatusCode.TooManyRequests,
                        headers {
                            if (attempts == 1) append("x-request-id", "req_previous")
                            else if (empty) append("x-request-id", "")
                        })
                }) {
                    installOpenAiRetry(retry)
                    install(SSE)
                    install(SseCompatibility)
                }.use { client ->
                    val diagnostic = if (compaction) {
                        assertFailsWith<OpenAiHttpStreamException> {
                            retryOpenAiStreamingTransportWithBudget(retry) { budget ->
                                client.streamRemoteCompactionEvents(budget, 1000) {
                                    url("https://example.test/responses")
                                }.collectRemoteCompactionV2Response()
                            }
                        }.failureDiagnostic
                    } else {
                        val observed = mutableListOf<OpenAiResponseHeaders>()
                        client.streamResponseEvents(1000, retry, onResponseHeaders = { observed += it }) {
                            url("https://example.test/responses")
                        }.toList()
                        observed.last().failure
                    }
                    assertEquals(2, attempts)
                    assertEquals(null, diagnostic?.requestId)
                    assertEquals(null, diagnostic?.responseId)
                    assertEquals(null, diagnostic?.errorCode)
                    assertEquals("", diagnostic?.errorType)
                    assertEquals(null, diagnostic?.errorMessage)
                    assertFalse(diagnostic.toString().contains("req_previous"))
                    assertFalse(diagnostic.toString().contains("access-token"))
                }
            }
        }
    }

    test("compaction SSE failures retain the final request and response identifiers") {
        for (incomplete in listOf(false, true)) {
            var attempts = 0
            val retry = OpenAiClientRetryConfig(maxRetries = 1, baseDelayMillis = 1, maxDelayMillis = 1, randomizationMillis = 0)
            HttpClient(SseMockEngine {
                attempts++
                val kind = if (incomplete) "incomplete" else "failed"
                val body = """data: {"type":"response.$kind","response":{"id":"resp_$attempts","error":{"code":"custom_$attempts","type":"kind_$attempts","message":"message_$attempts"},"incomplete_details":{"reason":"reason_$attempts"}}}

"""
                respond(body, HttpStatusCode.OK, headers {
                    append(HttpHeaders.ContentType, "text/event-stream")
                    append("x-oai-request-id", "req_sse_$attempts")
                })
            }) {
                installOpenAiRetry(retry)
                install(SSE)
                install(SseCompatibility)
            }.use { client ->
                val failure = assertFailsWith<OpenAiRemoteCompactionV2StreamFailureException> {
                    retryOpenAiStreamingTransportWithBudget(retry) { budget ->
                        client.streamRemoteCompactionEvents(budget, 1000) {
                            url("https://example.test/responses")
                        }.collectRemoteCompactionV2Response()
                    }
                }
                assertEquals(2, attempts)
                assertEquals("req_sse_2", failure.failureDiagnostic?.requestId)
                assertEquals("resp_2", failure.failureDiagnostic?.responseId)
                assertEquals("custom_2", failure.failureDiagnostic?.errorCode)
                assertEquals("kind_2", failure.failureDiagnostic?.errorType)
                assertEquals("message_2", failure.failureDiagnostic?.errorMessage)
                assertEquals("reason_2", failure.failureDiagnostic?.incompleteReason)
                assertFalse(failure.failureDiagnostic.toString().contains("req_sse_1"))
            }
        }
    }

    test("compaction missing terminal retains latest response fields") {
        val retry = OpenAiClientRetryConfig(maxRetries = 0)
        HttpClient(SseMockEngine {
            respond(
                """data: {"type":"response.in_progress","response":{"id":"resp_partial","status":"incomplete","error":{"code":"vendor_code","type":"vendor_type","message":"vendor message"},"incomplete_details":{"reason":"vendor_reason"}}}

""",
                HttpStatusCode.OK,
                headers { append(HttpHeaders.ContentType, "text/event-stream"); append("x-request-id", "req_partial") },
            )
        }) {
            install(SSE)
            install(SseCompatibility)
        }.use { client ->
            val failure = assertFailsWith<OpenAiRemoteCompactionV2StreamIncompleteException> {
                retryOpenAiStreamingTransportWithBudget(retry) { budget ->
                    client.streamRemoteCompactionEvents(budget, 1000) {
                        url("https://example.test/responses")
                    }.collectRemoteCompactionV2Response()
                }
            }
            assertEquals(OpenAiFailureTermination.MissingTerminal, failure.failureDiagnostic?.termination)
            assertEquals("req_partial", failure.failureDiagnostic?.requestId)
            assertEquals("resp_partial", failure.failureDiagnostic?.responseId)
            assertEquals("vendor_code", failure.failureDiagnostic?.errorCode)
            assertEquals("vendor_type", failure.failureDiagnostic?.errorType)
            assertEquals("vendor message", failure.failureDiagnostic?.errorMessage)
            assertEquals("vendor_reason", failure.failureDiagnostic?.incompleteReason)
        }
    }

    test("ordinary HTTP client error remains nonretryable with the production retry plugin") {
        var attempts = 0
        val retry = OpenAiClientRetryConfig(
            maxRetries = 2, baseDelayMillis = 1, maxDelayMillis = 1, randomizationMillis = 0,
        )
        HttpClient(SseMockEngine {
            attempts++
            respond("""{"error":{"code":"invalid_request_error","message":"Bearer access-token"}}""", HttpStatusCode.BadRequest,
                headers {
                    append("x-request-id", "req_fallback")
                    append("x-oai-request-id", "req_preferred/nonstandard")
                })
        }) {
            installOpenAiRetry(retry)
            install(SSE)
            install(SseCompatibility)
        }.use { client ->
            val failure = assertFailsWith<OpenAiHttpStreamException> {
                client.streamResponseEvents(1000, retry) {
                    url("https://example.test/responses")
                }.toList()
            }
            assertEquals(400, failure.status)
            assertEquals("req_preferred/nonstandard", failure.failureDiagnostic?.requestId)
            assertEquals("Bearer access-token", failure.failureDiagnostic?.errorMessage)
        }
        assertEquals(1, attempts)
    }

    test("oversized HTTP error fails safely without retrying or exposing its body") {
        var attempts = 0
        val retry = OpenAiClientRetryConfig(
            maxRetries = 2, baseDelayMillis = 1, maxDelayMillis = 1, randomizationMillis = 0,
        )
        HttpClient(SseMockEngine {
            attempts++
            respond("private prompt" + "x".repeat(MaxOpenAiErrorBodyBytes.toInt()), HttpStatusCode.ServiceUnavailable)
        }) {
            installOpenAiRetry(retry)
            install(SSE)
            install(SseCompatibility)
        }.use { client ->
            val failure = assertFailsWith<OpenAiHttpStreamException> {
                client.streamResponseEvents(1000, retry) {
                    url("https://example.test/responses")
                }.toList()
            }
            assertEquals(503, failure.status)
            assertFalse(failure.toString().contains("private prompt"))
        }
        assertEquals(1, attempts)
    }

    test("bounded error reader returns immediately after overflow even if the stream remains open") {
        coroutineScope {
            val channel = ByteChannel(autoFlush = true)
            val producer = launch {
                channel.writeFully(ByteArray(MaxOpenAiErrorBodyBytes.toInt() + 1))
                awaitCancellation()
            }
            try {
                val bytes = withTimeout(5_000) { channel.readBoundedErrorBody() }
                assertEquals(MaxOpenAiErrorBodyBytes + 1, bytes.size.toLong())
            } finally {
                channel.cancel()
                producer.cancelAndJoin()
            }
        }
    }

    test("deeply nested HTTP error fails safely without retrying") {
        var attempts = 0
        val retry = OpenAiClientRetryConfig(
            maxRetries = 2, baseDelayMillis = 1, maxDelayMillis = 1, randomizationMillis = 0,
        )
        val body = """{"error":""" + "[".repeat(65) + "0" + "]".repeat(65) + "}"
        HttpClient(SseMockEngine {
            attempts++
            respond(body, HttpStatusCode.ServiceUnavailable)
        }) {
            installOpenAiRetry(retry)
            install(SSE)
            install(SseCompatibility)
        }.use { client ->
            val failure = assertFailsWith<OpenAiHttpStreamException> {
                client.streamResponseEvents(1000, retry) {
                    url("https://example.test/responses")
                }.toList()
            }
            assertEquals(503, failure.status)
        }
        assertEquals(1, attempts)
    }

    test("simulated compaction moderation metadata stops on the first attempt") {
        var attempts = 0
        val body = """data: {"type":"response.metadata","response_id":"resp_metadata","metadata":{"openai_chatgpt_moderation_metadata":{"prompt":{"cyber_security":{"outputs":[{"is_blocked":true}]}}}}}

"""
        HttpClient(SseMockEngine {
            attempts++
            respond(body, HttpStatusCode.OK, headers {
                append(HttpHeaders.ContentType, "text/event-stream")
                append("x-request-id", "req_metadata")
            })
        }) {
            install(SSE)
            install(SseCompatibility)
        }.use { client ->
            val failure = assertFailsWith<OpenAiModerationBlockedException> {
                retryOpenAiStreamingTransportWithBudget(OpenAiClientRetryConfig(maxRetries = 2)) { budget ->
                    client.streamRemoteCompactionEvents(budget, 1000) {
                        url("https://example.test/responses")
                    }.collectRemoteCompactionV2Response()
                }
            }
            assertEquals(OpenAiFailureReason.CyberSecurityBlocked, failure.failureDiagnostic?.reason)
            assertEquals(200, failure.failureDiagnostic?.httpStatus)
            assertEquals(0, failure.retries)
            assertEquals("req_metadata", failure.failureDiagnostic?.requestId)
            assertEquals("resp_metadata", failure.failureDiagnostic?.responseId)
            assertFalse(failure.failureDiagnostic.toString().contains("private prompt"))
            assertFalse(failure.failureDiagnostic.toString().contains("access-token"))
        }
        assertEquals(1, attempts)
    }

    test("simulated compaction HTTP 5xx keeps its retry budget and final status") {
        var attempts = 0
        HttpClient(SseMockEngine {
            attempts++
            respond("private prompt", HttpStatusCode.ServiceUnavailable,
                headers { append("x-request-id", "compact_http_$attempts") })
        }) {
            installOpenAiRetry(
                OpenAiClientRetryConfig(maxRetries = 2, baseDelayMillis = 1, maxDelayMillis = 1, randomizationMillis = 0),
            )
            install(SSE)
            install(SseCompatibility)
        }.use { client ->
            val failure = assertFailsWith<OpenAiHttpStreamException> {
                retryOpenAiStreamingTransportWithBudget(
                    OpenAiClientRetryConfig(maxRetries = 2, baseDelayMillis = 1, maxDelayMillis = 1, randomizationMillis = 0),
                ) { budget ->
                    client.streamRemoteCompactionEvents(budget, 1000) {
                        url("https://example.test/responses")
                    }.collectRemoteCompactionV2Response()
                }
            }
            assertEquals(503, failure.status)
            assertEquals(2, failure.retries)
            assertEquals("compact_http_3", failure.failureDiagnostic?.requestId)
            assertFalse(failure.failureDiagnostic.toString().contains("compact_http_2"))
            assertFalse(failure.toString().contains("private prompt"))
        }
        assertEquals(3, attempts)
    }
}

@OptIn(InternalAPI::class)
private class SseMockEngine(private val handler: MockRequestHandler) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine =
        object : MockEngine(MockEngineConfig().apply(block).apply { addHandler(handler) }) {
            override val supportedCapabilities = super.supportedCapabilities + SSECapability

            override suspend fun execute(data: HttpRequestData): HttpResponseData {
                val response = super.execute(data)
                val body = response.body as ByteReadChannel
                val adapted = data.attributes.getOrNull(ResponseAdapterAttributeKey)?.adapt(
                    data,
                    response.statusCode,
                    response.headers,
                    body,
                    data.body,
                    response.callContext,
                ) ?: body
                return HttpResponseData(
                    response.statusCode,
                    response.requestTime,
                    response.headers,
                    response.version,
                    adapted,
                    response.callContext,
                )
            }
        }
}
