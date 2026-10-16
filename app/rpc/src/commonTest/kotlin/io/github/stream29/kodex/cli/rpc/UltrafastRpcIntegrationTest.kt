package io.github.stream29.kodex.cli.rpc

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.contract.TokenCountKind
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.rpc.models.AgentStateValue
import io.github.stream29.kodex.tool.multiagent.SuggestedSubagentTask
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.rpc.RpcCall
import kotlinx.rpc.RpcClient
import kotlin.test.*

val ultrafastRpcIntegrationTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("real JSON RPC preserves Ultrafast through defaults draft materialization CAS reopen fork and child creation") {
        val requests = mutableListOf<ResponsesApiRequest>()
        frontend(onRequest = { requests.add(it) }) {
            val old = services.global.getSettings()
            val defaults = old.copy(newSession = old.newSession.copy(serviceTier = ServiceTier.Ultrafast))
            assertTrue(services.global.compareAndSetSettings(old, defaults))
            assertEquals(ServiceTier.Ultrafast, services.global.getSettings().newSession.serviceTier)
            val draft = RpcSessionDraft(
                KodexAgentSettings(defaults.newSession.model, serviceTier = defaults.newSession.serviceTier),
                views, this,
            )
            try {
                assertTrue(services.global.getSessionCatalog(true).isEmpty())
                draft.composer.update("first message")
                val view = draft.materialize()
                val binding = view.current()
                binding.state.first { it == AgentStateValue.AssistantMessage }
                binding.running.first { !it }
                assertEquals(ServiceTier.Ultrafast, requests.single().serviceTier)
                assertEquals(ServiceTier.Ultrafast, binding.settings.value.serviceTier)
                // Updating defaults only affects future drafts, not this persisted Session.
                assertTrue(services.global.compareAndSetSettings(defaults, old))
                assertEquals(ServiceTier.Ultrafast, binding.settings.value.serviceTier)
                val agent = requireNotNull(view.agent.value)
                agent.updateModel(OpenAiModelId("changed-model"))
                binding.settings.first { it.model == OpenAiModelId("changed-model") }
                assertEquals(ServiceTier.Ultrafast, binding.settings.value.serviceTier)
                val current = binding.settings.value
                assertFalse(binding.settings.compareAndSet(current.copy(serviceTier = ServiceTier.Default), current))
                val forked = services.global.forkSession(view.index)
                val fork = views.open(forked)
                assertEquals(ServiceTier.Ultrafast, fork.current().settings.value.serviceTier)
                val children = services.global.createSuggestedSessions(
                    listOf(SuggestedSubagentTask("child", "independent prompt")), current,
                )
                val child = views.open(children.single().sessionIndex)
                assertEquals(ServiceTier.Ultrafast, child.current().settings.value.serviceTier)
                views.release(view)
                val reopened = views.open(view.index)
                assertEquals(ServiceTier.Ultrafast, reopened.current().settings.value.serviceTier)
            } finally {
                draft.close()
            }
        }
    }
    test("returned tier remains captured diagnostics after settings change without warning or error on reopen") {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        frontend(response = {
            started.complete(Unit)
            finish.await()
            tierAnswer("different", "default")
        }) {
            val view = views.open(services.global.createSession(
                KodexAgentSettings(OpenAiModelId("captured"), serviceTier = ServiceTier.Ultrafast),
            ))
            val binding = view.current()
            val agent = requireNotNull(view.agent.value)
            try {
                agent.submit(listOf(ContentItem.InputText("test response")))
                started.await()
                agent.updateServiceTier(ServiceTier.Default)
                binding.settings.first { it.serviceTier == ServiceTier.Default }
                finish.complete(Unit)
                binding.state.first { it == AgentStateValue.AssistantMessage }
                binding.running.first { !it }
                val snapshot = assertNotNull(binding.tokenCount.first { it?.kind == TokenCountKind.Response })
                assertEquals("ultrafast", snapshot.diagnostics?.requestedServiceTier)
                assertEquals("default", snapshot.diagnostics?.serviceTier)
                assertEquals("captured", snapshot.diagnostics?.requestedModel)
                assertEquals("different", snapshot.diagnostics?.responseId)
                assertEquals(ServiceTier.Default, binding.settings.value.serviceTier)
                assertNull(agent.notification.value)
                assertTrue(failures.value.isEmpty())
                views.release(view)
                val reopened = views.open(view.index)
                yield(); yield()
                assertNull(requireNotNull(reopened.agent.value).notification.value)
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
            decorate = { delegate ->
                object : RpcClient by delegate {
                    override suspend fun <T> call(call: RpcCall): T {
                        if (call.callableName == "clearPending") error("fixture operation failed")
                        return delegate.call(call)
                    }
                }
            },
        ) {
            val view = views.open(services.global.createSession(
                KodexAgentSettings(OpenAiModelId("test"), serviceTier = ServiceTier.Ultrafast),
            ))
            val binding = view.current()
            val agent = requireNotNull(view.agent.value)
            try {
                agent.submit(listOf(ContentItem.InputText("test")))
                started.await()
                agent.clearPending()
                assertEquals("fixture operation failed", failures.first { it.size == 1 }.single().message)
                assertNull(agent.notification.value)
                finish.complete(Unit)
                binding.tokenCount.first { it?.kind == TokenCountKind.Response }
                binding.running.first { !it }
                yield(); yield()
                assertNull(agent.notification.value)
                assertEquals(1, failures.value.size)
                assertEquals("priority", binding.tokenCount.value?.diagnostics?.serviceTier)
                assertEquals(ServiceTier.Ultrafast, binding.settings.value.serviceTier)
            } finally {
                finish.complete(Unit)
            }
        }
    }
    for (actual in listOf(null, "", "default", "priority", "future-tier", "ultrafast")) {
        test("successful Ultrafast response with reported tier '$actual' is diagnostics only") {
            val requests = mutableListOf<ResponsesApiRequest>()
            frontend(onRequest = requests::add, response = { tierAnswer("successful", actual) }) {
                val view = views.open(services.global.createSession(
                    KodexAgentSettings(OpenAiModelId("captured"), serviceTier = ServiceTier.Ultrafast),
                ))
                val binding = view.current()
                val agent = requireNotNull(view.agent.value)
                agent.submit(listOf(ContentItem.InputText("test response")))
                binding.state.first { it == AgentStateValue.AssistantMessage }
                binding.running.first { !it }
                val snapshot = assertNotNull(binding.tokenCount.first { it?.kind == TokenCountKind.Response })
                assertEquals(ServiceTier.Ultrafast, requests.single().serviceTier)
                assertEquals(ServiceTier.Ultrafast, binding.settings.value.serviceTier)
                assertEquals("ultrafast", snapshot.diagnostics?.requestedServiceTier)
                assertEquals(actual, snapshot.diagnostics?.serviceTier)
                assertNull(agent.notification.value)
                assertTrue(failures.value.isEmpty())
            }
        }
    }
}

private suspend fun FlowCollector<ResponsesStreamEvent>.tierAnswer(id: String, actual: String?) {
    emit(ResponsesStreamEvent.OutputItemDone(0, ResponseItem.Message(
        id = ResponseItemId("answer-$id"), role = MessageRole.Assistant,
        content = listOf(ContentItem.OutputText("answer")),
    )))
    emit(ResponsesStreamEvent.Completed(Response(
        id = id, serviceTier = actual, usage = TokenUsage(20, 3, 23), endTurn = true,
    )))
}
