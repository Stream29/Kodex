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
import kotlin.test.assertNotNull

val explicitReasoningHttpTest by testSuite {
    test("real client sends explicit effort to responses compaction and search on loopback") {
        data class Captured(val path: String, val body: String, val beta: String?)
        val captured = LinkedBlockingQueue<Captured>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val beta = exchange.requestHeaders.getFirst("x-codex-beta-features")
            captured.add(Captured(exchange.requestURI.path, exchange.requestBody.bufferedReader().use { it.readText() }, beta))
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
                    val request = ResponsesApiRequest(OpenAiModelId("test"), emptyList(), reasoning = Reasoning(effort))
                    client.createResponse(request).collect()
                    client.createRemoteCompactionV2Response(request, null, "{}", "window")
                    client.search(SearchRequest("test", request.model, request.reasoning))
                    for ((position, path) in listOf("/responses", "/responses", "/alpha/search").withIndex()) {
                        val actual = assertNotNull(captured.poll(5, TimeUnit.SECONDS))
                        assertEquals(path, actual.path)
                        assertEquals(if (position == 1) "remote_compaction_v2" else null, actual.beta)
                        val reasoning = OpenAiJsonCodec.parseToJsonElement(actual.body).jsonObject["reasoning"]!!.jsonObject
                        assertEquals(JsonPrimitive(if (effort == ReasoningEffort.Medium) "medium" else "low"), reasoning["effort"])
                        assertFalse("summary" in reasoning)
                        assertFalse("context" in reasoning)
                    }
                }
            }
          }
        } finally {
            server.stop(0)
        }
    }
}
