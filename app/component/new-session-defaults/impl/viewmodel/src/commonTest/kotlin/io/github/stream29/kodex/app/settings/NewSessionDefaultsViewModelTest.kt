@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.app.settings

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.settings.contract.*
import io.github.stream29.kodex.cli.settings.KodexNewSessionSettings
import io.github.stream29.kodex.openai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

val newSessionDefaultsViewModelTest by testSuite {
    test("canonical defaults and ordered catalog plus current; only defaults change revision") {
        runTest {
            val ports = DefaultsPorts()
            ports.models.value = listOf(info("z"), info("a"), info("z"))
            val vm = createNewSessionDefaultsViewModel(ports, this)
            try {
                assertEquals(ports.defaults.value, vm.state.value.settings)
                assertEquals(listOf("z", "a", "current"), vm.state.value.modelOptions.map { it.value })
                ports.models.value = listOf(info("a"), info("current"))
                runCurrent()
                assertEquals(0L, vm.state.value.revision)
                assertEquals(listOf("a", "current"), vm.state.value.modelOptions.map { it.value })
                ports.defaults.value = ports.defaults.value.copy(serviceTier = ServiceTier.Fast)
                runCurrent()
                assertEquals(1L, vm.state.value.revision)
                ports.models.value = emptyList()
                runCurrent()
                assertEquals(1L, vm.state.value.revision)
                assertEquals(listOf(OpenAiModelId("current")), vm.state.value.modelOptions)
            } finally { vm.close() }
        }
    }
    test("all four typed admissions capture just the canonical field baseline without optimistic update") {
        runTest {
            val ports = DefaultsPorts()
            val vm = createNewSessionDefaultsViewModel(ports, this)
            try {
                val original = ports.defaults.value
                vm.updateModel(0, OpenAiModelId("next"))
                vm.updateReasoningEffort(0, ReasoningEffort.Max)
                vm.updateServiceTier(0, ServiceTier.Flex)
                vm.updateRequestUserInputMode(0, RequestUserInputMode.NoQuestion)
                assertEquals(listOf(
                    "model" to (original.model to OpenAiModelId("next")),
                    "effort" to (original.reasoningEffort to ReasoningEffort.Max),
                    "tier" to (original.serviceTier to ServiceTier.Flex),
                    "questions" to (original.requestUserInputMode to RequestUserInputMode.NoQuestion),
                ), ports.admissions)
                assertEquals(original, vm.state.value.settings)
                assertEquals(0L, vm.state.value.revision)
            } finally { vm.close() }
        }
    }
    test("stale commands are rejected even before observation runs; catalog change does not stale commands") {
        runTest {
            val ports = DefaultsPorts()
            val vm = createNewSessionDefaultsViewModel(ports, this)
            try {
                vm.updateModel(1, OpenAiModelId("stale"))
                ports.models.value = listOf(info("catalog"))
                vm.updateModel(0, OpenAiModelId("admitted"))
                ports.defaults.value = ports.defaults.value.copy(serviceTier = ServiceTier.Fast)
                vm.updateModel(0, OpenAiModelId("old"))
                assertEquals(1L, vm.state.value.revision)
                assertEquals(1, ports.admissions.size)
            } finally { vm.close() }
        }
    }
    test("same value admission is not suppressed and rejected admission neither fabricates failure nor advances revision") {
        runTest {
            val ports = DefaultsPorts()
            ports.result = NewSessionDefaultsAdmission.Rejected
            val vm = createNewSessionDefaultsViewModel(ports, this)
            try {
                vm.updateModel(0, ports.defaults.value.model)
                assertEquals(1, ports.admissions.size)
                assertEquals(0L, vm.state.value.revision)
                assertTrue(ports.failures.isEmpty())
            } finally { vm.close() }
        }
    }
    test("ordinary synchronous failures use shared authority while cancellation propagates without reporting") {
        runTest {
            val ports = DefaultsPorts()
            val vm = createNewSessionDefaultsViewModel(ports, this)
            try {
                val expected = IllegalStateException("admission")
                ports.failure = expected
                vm.updateModel(0, OpenAiModelId("failed"))
                assertEquals(listOf<Throwable>(expected), ports.failures)
                assertSame(ports.operationFailure, vm.operationFailure)
                assertTrue(vm.operationFailure.value)
                vm.dismissOperationFailure()
                assertFalse(vm.operationFailure.value)
                ports.failure = CancellationException("cancel")
                assertFailsWith<CancellationException> { vm.updateServiceTier(0, ServiceTier.Fast) }
                assertEquals(1, ports.failures.size)
            } finally { vm.close() }
        }
    }
    test("child close freezes projection but app-owned accepted queue drains frozen payloads") {
        runTest {
            val ports = DefaultsPorts()
            val vm = createNewSessionDefaultsViewModel(ports, this)
            val original = vm.state.value
            vm.updateModel(0, OpenAiModelId("queued"))
            vm.updateServiceTier(0, ServiceTier.Fast)
            vm.close()
            vm.close()
            vm.updateReasoningEffort(0, ReasoningEffort.High)
            vm.dismissOperationFailure()
            assertEquals(2, ports.pending.size)
            ports.drain()
            ports.models.value = listOf(info("after-close"))
            runCurrent()
            assertEquals(OpenAiModelId("queued"), ports.defaults.value.model)
            assertEquals(ServiceTier.Fast, ports.defaults.value.serviceTier)
            assertEquals(original.copy(active = false), vm.state.value)
            assertEquals(2, ports.admissions.size)
        }
    }
    test("owner cancellation stops observation without retracting app-owned accepted admission") {
        runTest {
            val ports = DefaultsPorts()
            val owner = Job()
            val vm = createNewSessionDefaultsViewModel(ports, CoroutineScope(coroutineContext + owner))
            vm.updateRequestUserInputMode(0, RequestUserInputMode.NoQuestion)
            runCurrent()
            owner.cancel()
            runCurrent()
            assertFalse(vm.state.value.active)
            ports.drain()
            assertEquals(RequestUserInputMode.NoQuestion, ports.defaults.value.requestUserInputMode)
        }
    }
}

private fun info(value: String) = ModelInfo(OpenAiModelId(value), value)
private class DefaultsPorts : NewSessionDefaultsDependencies {
    override val defaults = MutableStateFlow(KodexNewSessionSettings(model = OpenAiModelId("current")))
    override val models = MutableStateFlow<List<ModelInfo>>(emptyList())
    override val operationFailure = MutableStateFlow(false)
    val admissions = mutableListOf<Pair<String, Pair<Any, Any>>>()
    val pending = mutableListOf<() -> Unit>()
    val failures = mutableListOf<Throwable>()
    var result = NewSessionDefaultsAdmission.Accepted
    var failure: Throwable? = null
    private fun admit(
        field: String, expected: Any, requested: Any,
        apply: (KodexNewSessionSettings) -> KodexNewSessionSettings,
    ): NewSessionDefaultsAdmission {
        failure?.let { throw it }
        admissions += field to (expected to requested)
        if (result == NewSessionDefaultsAdmission.Accepted) pending += { defaults.value = apply(defaults.value) }
        return result
    }
    fun drain() { pending.toList().forEach { it() }; pending.clear() }
    override fun admitModel(expected: OpenAiModelId, requested: OpenAiModelId) =
        admit("model", expected, requested) { if (it.model == expected) it.copy(model = requested) else it }
    override fun admitReasoningEffort(expected: ReasoningEffort, requested: ReasoningEffort) =
        admit("effort", expected, requested) {
            if (it.reasoningEffort == expected) it.copy(reasoningEffort = requested) else it
        }
    override fun admitServiceTier(expected: ServiceTier, requested: ServiceTier) =
        admit("tier", expected, requested) { if (it.serviceTier == expected) it.copy(serviceTier = requested) else it }
    override fun admitRequestUserInputMode(expected: RequestUserInputMode, requested: RequestUserInputMode) =
        admit("questions", expected, requested) {
            if (it.requestUserInputMode == expected) it.copy(requestUserInputMode = requested) else it
        }
    override fun reportFailure(failure: Throwable) { failures += failure; operationFailure.value = true }
    override fun dismissFailure() { operationFailure.value = false }
}
