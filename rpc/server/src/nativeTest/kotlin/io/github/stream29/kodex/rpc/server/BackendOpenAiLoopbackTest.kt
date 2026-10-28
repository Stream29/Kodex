package io.github.stream29.kodex.rpc.server

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.OpenAiFailureReason
import io.github.stream29.kodex.openai.OpenAiSubscriptionAuthState
import io.github.stream29.kodex.openai.client.OpenAiClient
import io.github.stream29.kodex.openai.client.OpenAiClientConfig
import io.github.stream29.kodex.openai.client.OpenAiClientRetryConfig
import io.github.stream29.kodex.openai.client.OpenAiModerationBlockedException
import io.github.stream29.kodex.openai.client.test.InMemoryOpenAiAuthStore
import io.github.stream29.kodex.rpc.models.Notification
import io.github.stream29.kodex.utils.rpcexception.AgentRunFailureReported
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val token = "loopback-synthetic-bearer-secret"
private const val bodySecret = "loopback-synthetic-provider-echo"
private const val requestId = "loopback-synthetic-request-id"
private const val prompt = "loopback-synthetic-private-prompt"

private enum class Scenario { Policy, AutoCompact, Transient, Cancel }

val backendOpenAiLoopbackTest by testSuite(compartment = { TestCompartment.RealTime }) {
    for (status in listOf(HttpStatusCode.TooManyRequests, HttpStatusCode.ServiceUnavailable)) {
        for (automatic in listOf(false, true)) {
            test("real client ${status.value} policy reaches RPC once, automatic=$automatic") {
                withLoopback(if (automatic) Scenario.AutoCompact else Scenario.Policy, status) { client, ordinary, compact, _ ->
                    runtimeFixture(client) { host, index, rpc ->
                        if (automatic) {
                            val settings = BackendSettingsTimelineRpc(host)
                            val before = settings.get(index, settings.getCacheNonce(index), settings.getLatestIndex(index))
                            assertTrue(settings.compareAndSet(index, before, before.copy(autoCompactionTokenLimit = 20)))
                        }
                        val events = mutableListOf<Notification>()
                        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                            rpc.notifications.flow.collect { events += it }
                        }
                        try {
                            rpc.appendUserMessage(index, listOf(ContentItem.InputText(prompt)))
                            val failure = assertFailsWithReported { rpc.resume(index) }
                            yield()
                            assertEquals(1, ordinary.value)
                            assertEquals(if (automatic) 1 else 0, compact.value)
                            val stop = assertIs<Notification.Stop.UnhandledError>(events.single())
                            val moderation = assertIs<OpenAiModerationBlockedException>(failure.suppressedExceptions.single())
                            assertEquals(status.value, moderation.failureDiagnostic?.httpStatus)
                            assertEquals(OpenAiFailureReason.ContentPolicyViolation, moderation.failureDiagnostic?.reason)
                            assertEquals(requestId, moderation.failureDiagnostic?.requestId)
                            if (automatic) assertEquals(0, moderation.retries)
                            assertEquals(index, stop.sessionIndex)
                            assertEquals(stop.message, failure.diagnostic)
                            assertTrue(stop.message.orEmpty().contains("moderation"))
                            for (secret in listOf(token, bodySecret, requestId, prompt)) {
                                assertFalse(stop.message.orEmpty().contains(secret))
                                assertFalse(failure.diagnostic.orEmpty().contains(secret))
                                assertFalse(failure.suppressedExceptions.joinToString().contains(secret))
                            }
                        } finally {
                            collector.cancelAndJoin()
                        }
                    }
                }
            }
        }

        test("real client ${status.value} retains HTTP and Agent retry budgets") {
            withLoopback(Scenario.Transient, status) { client, ordinary, compact, _ ->
                runtimeFixture(client) { _, index, rpc ->
                    val events = mutableListOf<Notification>()
                    val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                        rpc.notifications.flow.collect { events += it }
                    }
                    try {
                        rpc.appendUserMessage(index, listOf(ContentItem.InputText(prompt)))
                        val failure = assertFailsWithReported { rpc.resume(index) }
                        yield()
                        // 20 Agent retries plus the initial attempt, each with two HTTP retries.
                        assertEquals(63, ordinary.value)
                        assertEquals(0, compact.value)
                        val stop = assertIs<Notification.Stop.UnhandledError>(events.single())
                        assertEquals(stop.message, failure.diagnostic)
                        for (secret in listOf(token, bodySecret, requestId, prompt)) {
                            assertFalse(stop.message.orEmpty().contains(secret))
                        }
                    } finally {
                        collector.cancelAndJoin()
                    }
                }
            }
        }
    }

    test("real client cancellation during an unfinished response produces no Stop") {
        withLoopback(Scenario.Cancel, HttpStatusCode.ServiceUnavailable) { client, ordinary, compact, entered ->
            runtimeFixture(client) { _, index, rpc ->
                val events = mutableListOf<Notification>()
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    rpc.notifications.flow.collect { events += it }
                }
                try {
                    rpc.appendUserMessage(index, listOf(ContentItem.InputText(prompt)))
                    val run = async { rpc.resume(index) }
                    withTimeout(5_000) { entered.await() }
                    rpc.cancelRunningTurn(index)
                    run.join()
                    yield()
                    assertTrue(run.isCancelled)
                    assertFalse(rpc.getRunningTurn(index))
                    assertEquals(1, ordinary.value)
                    assertEquals(0, compact.value)
                    assertTrue(events.isEmpty())
                } finally {
                    collector.cancelAndJoin()
                }
            }
        }
    }
}

private suspend fun assertFailsWithReported(block: suspend () -> Unit): AgentRunFailureReported =
    kotlin.test.assertFailsWith<AgentRunFailureReported> { block() }

private suspend fun withLoopback(
    scenario: Scenario,
    status: HttpStatusCode,
    block: suspend (OpenAiClient, MutableStateFlow<Int>, MutableStateFlow<Int>, CompletableDeferred<Unit>) -> Unit,
) {
    val ordinary = MutableStateFlow(0)
    val compact = MutableStateFlow(0)
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
        routing {
            post("/responses") {
                val isCompact = call.request.headers["x-codex-beta-features"] == "remote_compaction_v2"
                if (isCompact) compact.update { it + 1 } else ordinary.update { it + 1 }
                call.response.header("x-request-id", requestId)
                when {
                    scenario == Scenario.AutoCompact && !isCompact -> call.respondText(
                        """data: {"type":"response.completed","response":{"id":"loopback-response","end_turn":false,"usage":{"input_tokens":15,"output_tokens":5,"total_tokens":20}}}""" + "\n\n",
                        ContentType.Text.EventStream,
                    )
                    scenario == Scenario.Cancel -> call.respondBytesWriter(ContentType.Application.Json, status) {
                        writeStringUtf8("{")
                        flush()
                        entered.complete(Unit)
                        release.await()
                    }
                    else -> {
                        val code = if (scenario == Scenario.Transient) "rate_limit_exceeded" else "content_policy_violation"
                        call.respondText(
                            """{"error":{"code":"$code","message":"$token $bodySecret $prompt"}}""",
                            ContentType.Application.Json,
                            status,
                        )
                    }
                }
            }
        }
    }
    try {
        server.startSuspend()
        val base = "http://127.0.0.1:${server.engine.resolvedConnectors().single().port}"
        val client = OpenAiClient(
            InMemoryOpenAiAuthStore(OpenAiSubscriptionAuthState(token, null)),
            OpenAiClientConfig(
                baseUrl = base,
                accountBaseUrl = base,
                retry = OpenAiClientRetryConfig(maxRetries = 2, baseDelayMillis = 1, maxDelayMillis = 1, randomizationMillis = 0),
                remoteCompactionMaxRetries = 2,
                sseSocketTimeoutMillis = 10_000,
            ),
        )
        try {
            withTimeout(30_000) { block(client, ordinary, compact, entered) }
        } finally {
            client.close()
        }
    } finally {
        withContext(NonCancellable) {
            release.complete(Unit)
            server.stopSuspend()
        }
    }
}
