package io.github.stream29.kodex.openai.client

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.CodexResponsesClientMetadata
import io.github.stream29.kodex.openai.ImageEditRequest
import io.github.stream29.kodex.openai.ImageGenerationRequest
import io.github.stream29.kodex.openai.ImageResponse
import io.github.stream29.kodex.openai.ImageUrl
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.OpenAiResponseResult
import io.github.stream29.kodex.openai.OpenAiResult
import io.github.stream29.kodex.openai.OpenAiSubscriptionAuthState
import io.github.stream29.kodex.openai.Reasoning
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesApiRequest
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.openai.client.test.InMemoryOpenAiAuthStore
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

private val fastRetry = OpenAiClientRetryConfig(
    maxRetries = 0,
    baseDelayMillis = 1,
    maxDelayMillis = 1,
    randomizationMillis = 0,
)

private const val imageFixtureJson = """{"created":1,"data":[{"b64_json":"Zml4dHVyZQ=="}]}"""

val openAiImageAndRoutingNativeHttpTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("ordinary responses reject partial Codex identity before transport") {
        OpenAiClient(
            InMemoryOpenAiAuthStore(OpenAiSubscriptionAuthState(accessToken = "unused", accountId = null)),
        ).use { client ->
            assertFailsWith<IllegalArgumentException> {
                client.createResponse(
                    model = OpenAiModelId("test"),
                    input = emptyList(),
                    threadId = "thread",
                )
            }
        }
    }

    test("image timeout overrides succeed past ordinary request timeout without leaking") {
        val attempts = MutableStateFlow(0)
        val server = embeddedServer(ServerCIO, host = "127.0.0.1", port = 0) {
            routing {
                post("/images/{endpoint}") {
                    call.receiveText()
                    attempts.update { it + 1 }
                    delay(350)
                    call.respondText(imageFixtureJson, ContentType.Application.Json)
                }
                get("/models") {
                    delay(350)
                    call.respondText("""{"models":[]}""", ContentType.Application.Json)
                }
                post("/responses") {
                    call.receiveText()
                    delay(350)
                    call.respondText(
                        "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"id\":\"fixture\"}}\n\n",
                        ContentType.Text.EventStream,
                    )
                }
            }
        }
        try {
            server.startSuspend()
            val baseUrl = "http://127.0.0.1:${server.engine.resolvedConnectors().single().port}"
            OpenAiClient(
                InMemoryOpenAiAuthStore(OpenAiSubscriptionAuthState("loopback-only", null)),
                OpenAiClientConfig(
                    baseUrl = baseUrl,
                    requestTimeoutMillis = 150,
                    imageRequestTimeoutMillis = 2_000,
                    sseSocketTimeoutMillis = 2_000,
                    retry = fastRetry,
                ),
            ).use { client ->
                client.requestTestImage("generations").assertImageSuccess()
                client.requestTestImage("edits").assertImageSuccess()
                assertEquals(2, attempts.value)
                assertFailsWith<Throwable> { client.listModels() }.assertTransportTimeout()
                val events = client.createResponse(OpenAiModelId("fixture"), emptyList()).toList()
                assertIs<ResponsesStreamEvent.Completed>(events.single())
            }
        } finally {
            withContext(NonCancellable) { server.stopSuspend() }
        }
    }

    test("cancelling an image request does not retry or block subsequent calls") {
        val started = CompletableDeferred<Unit>()
        val attempts = MutableStateFlow(0)
        val server = embeddedServer(ServerCIO, host = "127.0.0.1", port = 0) {
            routing {
                post("/images/edits") {
                    call.receiveText()
                    attempts.update { it + 1 }
                    started.complete(Unit)
                    delay(800)
                    call.respondText(imageFixtureJson, ContentType.Application.Json)
                }
                get("/models") {
                    call.respondText("""{"models":[]}""", ContentType.Application.Json)
                }
            }
        }
        try {
            server.startSuspend()
            val baseUrl = "http://127.0.0.1:${server.engine.resolvedConnectors().single().port}"
            OpenAiClient(
                InMemoryOpenAiAuthStore(OpenAiSubscriptionAuthState("loopback-only", null)),
                OpenAiClientConfig(
                    baseUrl = baseUrl,
                    imageRequestTimeoutMillis = 2_000,
                    retry = fastRetry.copy(maxRetries = 1),
                ),
            ).use { client ->
                coroutineScope {
                    val request = launch { client.requestTestImage("edits") }
                    withTimeout(5_000) { started.await() }
                    withTimeout(5_000) { request.cancelAndJoin() }
                }
                assertEquals(1, attempts.value)
                assertIs<OpenAiResult.Success<*>>(client.listModels())
                assertEquals(1, attempts.value)
            }
        } finally {
            withContext(NonCancellable) { server.stopSuspend() }
        }
    }

    test("ultrafast routing hint and explicit reasoning wire fields do not leak across requests") {
        data class CapturedRequest(
            val path: String,
            val body: String,
            val routing: String?,
            val apiTier: String?,
            val beta: String?,
            val turnState: String?,
        )
        val captured = MutableStateFlow<List<CapturedRequest>>(emptyList())
        val server = embeddedServer(ServerCIO, host = "127.0.0.1", port = 0) {
            routing {
                post("/responses") {
                    val body = call.receiveText()
                    val beta = call.request.headers["x-codex-beta-features"]
                    captured.update {
                        it + CapturedRequest(
                            path = call.request.path(),
                            body = body,
                            routing = call.request.headers["x-codex-routing-hint"],
                            apiTier = call.request.headers["OpenAI-Service-Tier"],
                            beta = beta,
                            turnState = call.request.headers["x-codex-turn-state"],
                        )
                    }
                    val model = (OpenAiJsonCodec.parseToJsonElement(body).jsonObject["model"] as? JsonPrimitive)?.content
                    val rejection = model?.removePrefix("reject-")?.toIntOrNull()
                    if (rejection != null) {
                        call.respondText(
                            """{"error":{"message":"Denied","type":"invalid_request_error"}}""",
                            ContentType.Application.Json,
                            HttpStatusCode.fromValue(rejection),
                        )
                    } else {
                        call.response.header("x-codex-turn-state", "server-turn-state")
                        call.response.header("x-oai-request-id", "request-id")
                        val reply = buildString {
                            if (beta != null) {
                                append("event: response.output_item.done\ndata: ")
                                append(
                                    OpenAiJsonCodec.encodeToString(
                                        ResponsesStreamEvent.serializer(),
                                        ResponsesStreamEvent.OutputItemDone(
                                            0,
                                            ResponseItem.Compaction(encryptedContent = "fixture"),
                                        ),
                                    ),
                                )
                                append("\n\n")
                            }
                            append("event: response.completed\ndata: ")
                            append(
                                OpenAiJsonCodec.encodeToString(
                                    ResponsesStreamEvent.serializer(),
                                    ResponsesStreamEvent.Completed(Response(id = "fixture", serviceTier = "ultrafast")),
                                ),
                            )
                            append("\n\n")
                        }
                        call.respondText(reply, ContentType.Text.EventStream)
                    }
                }
                get("/models") {
                    captured.update {
                        it + CapturedRequest(
                            path = call.request.path(),
                            body = "",
                            routing = call.request.headers["x-codex-routing-hint"],
                            apiTier = call.request.headers["OpenAI-Service-Tier"],
                            beta = null,
                            turnState = null,
                        )
                    }
                    call.respondText("""{"models":[]}""", ContentType.Application.Json)
                }
            }
        }
        try {
            server.startSuspend()
            val baseUrl = "http://127.0.0.1:${server.engine.resolvedConnectors().single().port}"
            OpenAiClient(
                InMemoryOpenAiAuthStore(OpenAiSubscriptionAuthState("loopback-only", null)),
                OpenAiClientConfig(
                    baseUrl = baseUrl,
                    retry = fastRetry.copy(maxRetries = 2),
                    remoteCompactionMaxRetries = 2,
                ),
            ).use { client ->
                val model = OpenAiModelId("gpt-6-astra")
                for (tier in listOf(ServiceTier.Ultrafast, ServiceTier.Default, ServiceTier.Fast, ServiceTier.Flex)) {
                    var receivedTurnState: String? = null
                    var receivedRequestId: String? = null
                    client.createResponse(
                        model = model,
                        input = emptyList(),
                        reasoning = Reasoning(ReasoningEffort.Medium),
                        serviceTier = tier,
                        installationId = "installation",
                        threadId = "thread",
                        windowId = "window",
                        turnState = "client-turn-state",
                        onResponseHeaders = { headers ->
                            receivedTurnState = headers.turnState
                            receivedRequestId = headers.requestId
                        },
                    ).collect()
                    assertEquals("server-turn-state", receivedTurnState)
                    assertEquals("request-id", receivedRequestId)
                    val ordinary = captured.value.last()
                    val json = OpenAiJsonCodec.parseToJsonElement(ordinary.body).jsonObject
                    assertEquals(
                        if (tier == ServiceTier.Default) null else JsonPrimitive(tier.requestValue),
                        json["service_tier"],
                    )
                    assertEquals(
                        if (tier == ServiceTier.Ultrafast) "model=${model.value};tier=ultrafast" else null,
                        ordinary.routing,
                    )
                    assertNull(ordinary.apiTier)
                    assertEquals("client-turn-state", ordinary.turnState)
                    val wireReasoning = json.getValue("reasoning").jsonObject
                    assertEquals(JsonPrimitive("medium"), wireReasoning["effort"])
                    assertFalse("summary" in wireReasoning)
                    assertFalse("context" in wireReasoning)

                    client.createRemoteCompactionV2Response(
                        ResponsesApiRequest(
                            model = model,
                            input = emptyList(),
                            reasoning = Reasoning(ReasoningEffort.Low),
                            serviceTier = tier,
                            clientMetadata = CodexResponsesClientMetadata(
                                installationId = "compact-installation",
                                threadId = "compact-thread",
                                windowId = "compact-window",
                                turnMetadata = "{}",
                            ),
                        ),
                    )
                    val compaction = captured.value.last()
                    assertEquals("remote_compaction_v2", compaction.beta)
                    assertEquals(
                        if (tier == ServiceTier.Ultrafast) "model=${model.value};tier=ultrafast" else null,
                        compaction.routing,
                    )
                }

                client.listModels()
                val modelsReq = captured.value.last()
                assertEquals("/models", modelsReq.path)
                assertNull(modelsReq.routing)

                for (status in listOf(400, 401, 403, 404)) {
                    val countBefore = captured.value.size
                    val failure = assertFails {
                        client.createResponse(
                            OpenAiModelId("reject-$status"),
                            emptyList(),
                            serviceTier = ServiceTier.Ultrafast,
                        ).collect()
                    }
                    assertFalse(failure is CancellationException)
                    assertEquals(countBefore + 1, captured.value.size)
                }
            }
        } finally {
            withContext(NonCancellable) { server.stopSuspend() }
        }
    }
}

private suspend fun OpenAiClient.requestTestImage(endpoint: String): OpenAiResponseResult<ImageResponse> =
    when (endpoint) {
        "generations" -> generateImage(ImageGenerationRequest("fixture", OpenAiModelId("fixture")))
        "edits" -> editImage(
            ImageEditRequest(
                listOf(ImageUrl("data:image/png;base64,Zml4dHVyZQ==")),
                "fixture",
                OpenAiModelId("fixture"),
            ),
        )
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
