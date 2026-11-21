package io.github.stream29.kodex.cli.rpc

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.LlmRequestMeta
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.rpc.models.AgentStateValue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.first
import kotlinx.rpc.RpcCall
import kotlinx.rpc.RpcClient
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

val ultrafastRpcIntegrationTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("real JSON RPC preserves Ultrafast through defaults draft submission CAS reopen and fork") {
        val requests = mutableListOf<ResponsesApiRequest>()
        frontend(onRequest = { requests.add(it) }) {
            val old = services.global.getSettings()
            val defaults = old.copy(newSession = old.newSession.copy(serviceTier = ServiceTier.Ultrafast))
            assertTrue(services.global.compareAndSetSettings(old, defaults))
            assertEquals(ServiceTier.Ultrafast, services.global.getSettings().newSession.serviceTier)
            val draft = RpcSessionDraft(home, views, global, this)
            try {
                assertTrue(services.global.getSessionCatalog(true).isEmpty())
                val revision = draft.composer.update("first message")
                val view = assertNotNull(draft.submit(revision, views::open))
                val binding = view.current()
                binding.state.first { it == AgentStateValue.AssistantMessage }
                binding.running.first { !it }
                assertEquals(ServiceTier.Ultrafast, requests.single().serviceTier)
                assertEquals(ServiceTier.Ultrafast, binding.settings.value.serviceTier)
                // Updating defaults changes the reusable draft, not this persisted Session.
                assertTrue(services.global.compareAndSetSettings(defaults, old))
                assertEquals(ServiceTier.Ultrafast, binding.settings.value.serviceTier)
                val agent = requireNotNull(view.rootAgent.value)
                agent.updateModel(OpenAiModelId("changed-model"))
                binding.settings.first { it.model == OpenAiModelId("changed-model") }
                assertEquals(ServiceTier.Ultrafast, binding.settings.value.serviceTier)
                val current = binding.settings.value
                assertFalse(binding.settings.compareAndSet(current.copy(serviceTier = ServiceTier.Default), current))
                val forked = services.global.forkAgent(view.agentId)
                val fork = views.open(forked)
                assertEquals(ServiceTier.Ultrafast, fork.current().settings.value.serviceTier)
                views.release(view)
                val reopened = views.open(view.sessionIndex)
                assertEquals(ServiceTier.Ultrafast, reopened.current().settings.value.serviceTier)
            } finally {
                draft.close()
            }
        }
    }
    test("returned tier remains captured diagnostics after settings change without warning or error on reopen") {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        frontend(responseReadElapsed = 5.milliseconds, response = {
            started.complete(Unit)
            finish.await()
            tierAnswer("different", "default")
        }) {
            val old = services.global.getSettings()
            assertTrue(services.global.compareAndSetSettings(old, old.copy(
                newSession = old.newSession.copy(model = OpenAiModelId("captured"), serviceTier = ServiceTier.Ultrafast),
            )))
            val view = views.open(services.global.createSession(home))
            val binding = view.current()
            val agent = requireNotNull(view.rootAgent.value)
            try {
                agent.submit(listOf(ContentItem.InputText("test response")))
                started.await()
                agent.updateServiceTier(ServiceTier.Default)
                binding.settings.first { it.serviceTier == ServiceTier.Default }
                finish.complete(Unit)
                binding.state.first { it == AgentStateValue.AssistantMessage }
                binding.running.first { !it }
                val snapshot = binding.tokenCount.first { it is LlmRequestMeta.Response } as LlmRequestMeta.Response
                assertEquals(5_000_000L, snapshot.elapsedNanoseconds)
                assertEquals(3L, snapshot.outputTokens)
                assertEquals("ultrafast", snapshot.diagnostics?.requestedServiceTier)
                assertEquals("default", snapshot.diagnostics?.serviceTier)
                assertEquals("captured", snapshot.diagnostics?.requestedModel)
                assertEquals("different", snapshot.diagnostics?.responseId)
                assertEquals(ServiceTier.Default, binding.settings.value.serviceTier)
                assertTrue(failures.value.isEmpty())
                views.release(view)
                val reopened = views.open(view.sessionIndex)
                yield(); yield()
                requireNotNull(reopened.rootAgent.value)
                assertTrue(failures.value.isEmpty())
            } finally {
                finish.complete(Unit)
            }
        }
    }
    test("a real operation error is reported once and a subsequent successful tier mismatch adds no warning") {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        frontend(
            response = { started.complete(Unit); finish.await(); tierAnswer("different", "priority") },
            responseReadElapsed = 5.milliseconds,
            decorate = { delegate ->
                object : RpcClient by delegate {
                    override suspend fun <T> call(call: RpcCall): T {
                        if (call.callableName == "clearPending") error("fixture operation failed")
                        return delegate.call(call)
                    }
                }
            },
        ) {
            val old = services.global.getSettings()
            assertTrue(services.global.compareAndSetSettings(old, old.copy(
                newSession = old.newSession.copy(model = OpenAiModelId("test"), serviceTier = ServiceTier.Ultrafast),
            )))
            val view = views.open(services.global.createSession(home))
            val binding = view.current()
            val agent = requireNotNull(view.rootAgent.value)
            try {
                agent.submit(listOf(ContentItem.InputText("test")))
                started.await()
                agent.clearPending()
                assertEquals("fixture operation failed", failures.first { it.size == 1 }.single().message)
                finish.complete(Unit)
                binding.tokenCount.first { it is LlmRequestMeta.Response }
                binding.running.first { !it }
                yield(); yield()
                assertEquals(1, failures.value.size)
                assertEquals("priority", (binding.tokenCount.value as? LlmRequestMeta.Response)?.diagnostics?.serviceTier)
                assertEquals(ServiceTier.Ultrafast, binding.settings.value.serviceTier)
            } finally {
                finish.complete(Unit)
            }
        }
    }
    for (actual in listOf(null, "", "default", "priority", "future-tier", "ultrafast")) {
        test("successful Ultrafast response with reported tier '$actual' is diagnostics only") {
            val requests = mutableListOf<ResponsesApiRequest>()
            frontend(onRequest = requests::add, responseReadElapsed = 5.milliseconds, response = { tierAnswer("successful", actual) }) {
                val old = services.global.getSettings()
                assertTrue(services.global.compareAndSetSettings(old, old.copy(
                    newSession = old.newSession.copy(model = OpenAiModelId("captured"), serviceTier = ServiceTier.Ultrafast),
                )))
                val view = views.open(services.global.createSession(home))
                val binding = view.current()
                val agent = requireNotNull(view.rootAgent.value)
                agent.submit(listOf(ContentItem.InputText("test response")))
                binding.state.first { it == AgentStateValue.AssistantMessage }
                binding.running.first { !it }
                val snapshot = binding.tokenCount.first { it is LlmRequestMeta.Response } as LlmRequestMeta.Response
                assertEquals(ServiceTier.Ultrafast, requests.single().serviceTier)
                assertEquals(ServiceTier.Ultrafast, binding.settings.value.serviceTier)
                assertEquals("ultrafast", snapshot.diagnostics?.requestedServiceTier)
                assertEquals(actual, snapshot.diagnostics?.serviceTier)
                assertTrue(failures.value.isEmpty())
            }
        }
    }
}

private suspend fun FlowCollector<ResponsesStreamEvent>.tierAnswer(id: String, actual: String?) {
    emit(ResponsesStreamEvent.Created(Response(id = id)))
    emit(ResponsesStreamEvent.OutputItemDone(0, ResponseItem.Message(
        id = ResponseItemId("answer-$id"), role = MessageRole.Assistant,
        content = listOf(ContentItem.OutputText("answer")),
    )))
    emit(ResponsesStreamEvent.Completed(Response(
        id = id, serviceTier = actual, usage = TokenUsage(20, 3, 23), endTurn = true,
    )))
}
