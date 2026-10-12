package io.github.stream29.kodex.openai.client

import com.sun.net.httpserver.HttpServer
import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.openai.client.test.InMemoryOpenAiAuthStore
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

val ultrafastRoutingHttpTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("one real client preserves every tier on responses and compaction with no routing leakage") {
        withTierLoopback { client, captured ->
            for (model in listOf(OpenAiModelId("gpt-6-astra"), OpenAiModelId("gpt-6.1-sol"))) {
                // Ultrafast first proves later requests do not inherit its hint.
                for (tier in listOf(ServiceTier.Ultrafast, ServiceTier.Default, ServiceTier.Fast, ServiceTier.Flex)) {
                    client.createResponse(
                        model = model, input = emptyList(), serviceTier = tier,
                        installationId = "installation", threadId = "thread", windowId = "window",
                        turnState = "outgoing-turn",
                    ).collect()
                    val ordinary = assertNotNull(captured.poll(5, TimeUnit.SECONDS))
                    ordinary.assertTier(model, tier)
                    assertNull(ordinary.beta)
                    assertEquals("installation", ordinary.installation)
                    assertEquals("window", ordinary.window)
                    assertNotNull(ordinary.turnMetadata)
                    assertEquals("outgoing-turn", ordinary.turnState)
                    assertEquals(JsonPrimitive("thread"),
                        OpenAiJsonCodec.parseToJsonElement(ordinary.body).jsonObject["prompt_cache_key"])

                    client.createRemoteCompactionV2Response(ResponsesApiRequest(
                        model = model, input = emptyList(), serviceTier = tier,
                        clientMetadata = CodexResponsesClientMetadata(
                            installationId = "compact-installation", threadId = "compact-thread",
                            windowId = "compact-window", turnMetadata = "{}",
                        ),
                    ))
                    val compaction = assertNotNull(captured.poll(5, TimeUnit.SECONDS))
                    compaction.assertTier(model, tier)
                    assertEquals("remote_compaction_v2", compaction.beta)
                    assertEquals("compact-installation", compaction.installation)
                    assertEquals("compact-window", compaction.window)
                    assertEquals("{}", compaction.turnMetadata)
                    assertNull(compaction.turnState)
                }
            }
            client.listModels()
            val models = assertNotNull(captured.poll(5, TimeUnit.SECONDS))
            assertEquals("/models", models.path)
            assertNull(models.routing)
            assertNull(models.apiTier)
            client.search(SearchRequest("query", OpenAiModelId("gpt-6-astra"), Reasoning()))
            val search = assertNotNull(captured.poll(5, TimeUnit.SECONDS))
            assertEquals("/alpha/search", search.path)
            assertNull(search.routing)
            assertNull(search.apiTier)
            // An operation that omits the optional tier keeps Default, as title generation does.
            client.createResponse(OpenAiModelId("gpt-6-astra"), emptyList()).collect()
            assertNotNull(captured.poll(5, TimeUnit.SECONDS)).assertTier(
                OpenAiModelId("gpt-6-astra"), ServiceTier.Default,
            )
        }
    }
    test("HTTP rejection preserves the explicit selection and neither retries nor downgrades client errors") {
        withTierLoopback { client, captured ->
            for (status in listOf(400, 401, 403)) {
                val model = OpenAiModelId("reject-$status")
                val ordinaryFailure = assertFailsWith<Throwable> {
                    client.createResponse(model, emptyList(), serviceTier = ServiceTier.Ultrafast).collect()
                }
                assertFalse(ordinaryFailure is CancellationException)
                assertNotNull(captured.poll(5, TimeUnit.SECONDS)).assertTier(model, ServiceTier.Ultrafast)
                assertNull(captured.poll())
                val compactionFailure = assertFailsWith<Throwable> {
                    client.createRemoteCompactionV2Response(
                        ResponsesApiRequest(model, emptyList(), serviceTier = ServiceTier.Ultrafast),
                    )
                }
                assertFalse(compactionFailure is CancellationException)
                assertNotNull(captured.poll(5, TimeUnit.SECONDS)).assertTier(model, ServiceTier.Ultrafast)
                assertNull(captured.poll())
            }
            client.createResponse(OpenAiModelId("recovered"), emptyList(), serviceTier = ServiceTier.Ultrafast).collect()
            assertNotNull(captured.poll(5, TimeUnit.SECONDS)).assertTier(OpenAiModelId("recovered"), ServiceTier.Ultrafast)
        }
    }
}

private data class TierHttpCapture(
    val path: String,
    val body: String,
    val routing: String?,
    val apiTier: String?,
    val beta: String?,
    val installation: String?,
    val window: String?,
    val turnMetadata: String?,
    val turnState: String?,
) {
    fun assertTier(model: OpenAiModelId, tier: ServiceTier) {
        assertEquals("/responses", path)
        val json = OpenAiJsonCodec.parseToJsonElement(body).jsonObject
        assertEquals(JsonPrimitive(model.value), json["model"])
        assertEquals(if (tier == ServiceTier.Default) null else JsonPrimitive(tier.requestValue), json["service_tier"])
        assertEquals(if (tier == ServiceTier.Ultrafast) "model=${model.value};tier=ultrafast" else null, routing)
        assertNull(apiTier)
        assertFalse("x-codex-routing-hint" in json)
    }
}

private suspend fun withTierLoopback(
    block: suspend (OpenAiClient, LinkedBlockingQueue<TierHttpCapture>) -> Unit,
) = withContext(Dispatchers.Default) {
    val captured = LinkedBlockingQueue<TierHttpCapture>()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
        try {
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            val headers = exchange.requestHeaders
            val beta = headers.getFirst("x-codex-beta-features")
            captured.add(TierHttpCapture(
                exchange.requestURI.path, body, headers.getFirst("x-codex-routing-hint"),
                headers.getFirst("OpenAI-Service-Tier"), beta,
                headers.getFirst("x-codex-installation-id"), headers.getFirst("x-codex-window-id"),
                headers.getFirst("x-codex-turn-metadata"), headers.getFirst("x-codex-turn-state"),
            ))
            val model = if (body.isNotBlank()) {
                (OpenAiJsonCodec.parseToJsonElement(body).jsonObject["model"] as? JsonPrimitive)?.content
            } else null
            val rejection = model?.removePrefix("reject-")?.toIntOrNull()
            val isModels = exchange.requestURI.path.endsWith("/models")
            val isSearch = exchange.requestURI.path.endsWith("/search")
            val reply = when {
                rejection != null -> """{"error":{"message":"Fixture access denied","type":"invalid_request_error"}}"""
                isModels -> """{"models":[]}"""
                isSearch -> """{"output":"fixture"}"""
                else -> buildString {
                    if (beta != null) {
                        append("event: response.output_item.done\ndata: ")
                        append(OpenAiJsonCodec.encodeToString(ResponsesStreamEvent.serializer(),
                            ResponsesStreamEvent.OutputItemDone(0, ResponseItem.Compaction(encryptedContent = "fixture"))))
                        append("\n\n")
                    }
                    append("event: response.completed\ndata: ")
                    append(OpenAiJsonCodec.encodeToString(ResponsesStreamEvent.serializer(),
                        ResponsesStreamEvent.Completed(Response(id = "fixture", serviceTier = "ultrafast"))))
                    append("\n\n")
                }
            }.toByteArray()
            exchange.responseHeaders.set("Content-Type",
                if (rejection != null || isModels || isSearch) "application/json" else "text/event-stream")
            exchange.sendResponseHeaders(rejection ?: 200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        } finally {
            exchange.close()
        }
    }
    server.start()
    try {
        OpenAiClient(
            InMemoryOpenAiAuthStore(OpenAiSubscriptionAuthState("loopback-only", null)),
            OpenAiClientConfig(
                baseUrl = "http://127.0.0.1:${server.address.port}",
                retry = OpenAiClientRetryConfig(maxRetries = 2, baseDelayMillis = 1, randomizationMillis = 0),
                remoteCompactionMaxRetries = 2,
            ),
        ).use { client -> block(client, captured) }
    } finally {
        server.stop(0)
    }
}
