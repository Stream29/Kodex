package io.github.stream29.kodex.openai.client

import com.sun.net.httpserver.HttpServer
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.openai.client.test.InMemoryOpenAiAuthStore
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

val explicitReasoningHttpTest by testSuite {
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

    test("real client sends explicit effort to responses compaction and search on loopback") {
        data class Captured(
            val path: String,
            val body: String,
            val beta: String?,
            val installationId: String?,
            val turnMetadata: String?,
            val windowId: String?,
            val turnState: String?,
        )
        val captured = LinkedBlockingQueue<Captured>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val beta = exchange.requestHeaders.getFirst("x-codex-beta-features")
            val turnState = exchange.requestHeaders.getFirst("x-codex-turn-state")
            captured.add(
                Captured(
                    exchange.requestURI.path,
                    exchange.requestBody.bufferedReader().use { it.readText() },
                    beta,
                    exchange.requestHeaders.getFirst("x-codex-installation-id"),
                    exchange.requestHeaders.getFirst("x-codex-turn-metadata"),
                    exchange.requestHeaders.getFirst("x-codex-window-id"),
                    turnState,
                ),
            )
            val search = exchange.requestURI.path.endsWith("/search")
            val body = if (search) {
                """{"output":"loopback"}"""
            } else {
                buildString {
                    if (beta != null) {
                        val event = ResponsesStreamEvent.OutputItemDone(0, ResponseItem.Compaction(encryptedContent = "test"))
                        append("event: response.output_item.done\ndata: ")
                        append(OpenAiJsonCodec.encodeToString(ResponsesStreamEvent.serializer(), event))
                        append("\n\n")
                    }
                    append("event: response.completed\ndata: ")
                    append(OpenAiJsonCodec.encodeToString(
                        ResponsesStreamEvent.serializer(), ResponsesStreamEvent.Completed(Response(id = "test")),
                    ))
                    append("\n\n")
                }
            }.toByteArray()
            exchange.responseHeaders.set("Content-Type", if (search) "application/json" else "text/event-stream")
            exchange.responseHeaders.set("x-codex-turn-state", "server-turn-state")
            exchange.responseHeaders.set("x-oai-request-id", "request-id")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        try {
          withContext(Dispatchers.Default) {
            OpenAiClient(
                InMemoryOpenAiAuthStore(OpenAiSubscriptionAuthState(accessToken = "loopback-only", accountId = null)),
                OpenAiClientConfig(
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                    retry = OpenAiClientRetryConfig(maxRetries = 0),
                    remoteCompactionMaxRetries = 0,
                ),
            ).use { client ->
                for (effort in listOf(ReasoningEffort.Medium, ReasoningEffort.Low)) {
                    val clientTurnState = "client-turn-state"
                    val model = OpenAiModelId("test")
                    val reasoning = Reasoning(effort)
                    var receivedTurnState: String? = null
                    var receivedRequestId: String? = null
                    client.createResponse(
                        model = model,
                        input = emptyList(),
                        reasoning = reasoning,
                        installationId = "installation",
                        threadId = "thread",
                        windowId = "window",
                        turnState = clientTurnState,
                        onResponseHeaders = { headers ->
                            receivedTurnState = headers.turnState
                            receivedRequestId = headers.requestId
                        },
                    ).collect()
                    client.createResponse(
                        model = model,
                        input = emptyList(),
                        reasoning = reasoning,
                        installationId = "installation",
                        threadId = "thread",
                        windowId = "window",
                        turnState = clientTurnState,
                    ).collect()
                    client.createRemoteCompactionV2Response(
                        ResponsesApiRequest(
                            model = model,
                            input = emptyList(),
                            reasoning = reasoning,
                            clientMetadata = CodexResponsesClientMetadata(
                                installationId = "installation",
                                threadId = "thread",
                                windowId = "window",
                                turnMetadata = "{}",
                            ),
                        ),
                    )
                    client.search(SearchRequest("test", model, reasoning))
                    for ((position, path) in listOf(
                        "/responses",
                        "/responses",
                        "/responses",
                        "/alpha/search",
                    ).withIndex()) {
                        val actual = assertNotNull(captured.poll(5, TimeUnit.SECONDS))
                        assertEquals(path, actual.path)
                        assertEquals(if (position < 2) "client-turn-state" else null, actual.turnState)
                        assertEquals(if (position < 3) "installation" else null, actual.installationId)
                        assertEquals(
                            when (position) {
                                0, 1 -> """{"installation_id":"installation","thread_id":"thread","window_id":"window","request_kind":"turn"}"""
                                2 -> "{}"
                                else -> null
                            },
                            actual.turnMetadata,
                        )
                        assertEquals(if (position < 3) "window" else null, actual.windowId)
                        if (position == 0) {
                            assertEquals("server-turn-state", receivedTurnState)
                            assertEquals("request-id", receivedRequestId)
                        }
                        assertEquals(if (position == 2) "remote_compaction_v2" else null, actual.beta)
                        val body = OpenAiJsonCodec.parseToJsonElement(actual.body).jsonObject
                        val wireReasoning = body["reasoning"]!!.jsonObject
                        assertEquals(
                            JsonPrimitive(if (effort == ReasoningEffort.Medium) "medium" else "low"),
                            wireReasoning["effort"],
                        )
                        assertFalse("summary" in wireReasoning)
                        assertFalse("context" in wireReasoning)
                        if (position < 2) {
                            assertEquals(JsonPrimitive("thread"), body["prompt_cache_key"])
                            assertEquals(
                                JsonPrimitive("thread"),
                                body["client_metadata"]?.jsonObject?.get("thread_id"),
                            )
                            assertFalse("turnState" in body)
                            assertFalse("turn_state" in body)
                        }
                    }
                }
            }
          }
        } finally {
            server.stop(0)
        }
    }
}
