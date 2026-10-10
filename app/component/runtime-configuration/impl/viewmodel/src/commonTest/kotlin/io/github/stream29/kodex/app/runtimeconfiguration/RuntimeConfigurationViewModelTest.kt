@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.app.runtimeconfiguration

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.ModelServiceTier
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.ReasoningEffortPreset
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

val runtimeConfigurationViewModelTest by testSuite {
    test("catalog order missing current model and duplicate first capabilities are pure projection") {
        runTest {
            val deps = RuntimePorts()
            val custom = ReasoningEffort.Custom("retained-custom")
            deps.configuration.value = deps.configuration.value.copy(
                reasoning = custom, tier = ServiceTier.Flex,
                requestUserInputMode = RequestUserInputMode.NoQuestion,
            )
            deps.models.value = listOf(
                info("second", listOf(ReasoningEffort.Low, ReasoningEffort.Max), ServiceTier.Fast),
                info("first", emptyList()),
                info("second", listOf(ReasoningEffort.High), ServiceTier.Flex),
            )
            val vm = createRuntimeConfigurationViewModel(deps, backgroundScope)
            runCurrent()
            assertEquals(deps.configuration.value, vm.state.value.configuration)
            val options = vm.state.value.modelOptions
            assertEquals(listOf("second", "first", "current"), options.map { it.model.value })
            assertEquals(listOf(ReasoningEffort.Low, ReasoningEffort.Max), options[0].efforts)
            assertEquals(listOf(ServiceTier.Default, ServiceTier.Fast), options[0].tiers)
            assertEquals(listOf(custom), options[1].efforts)
            assertEquals(listOf(custom), options[2].efforts)
            assertEquals(listOf(ServiceTier.Default), options[2].tiers)
            assertTrue(deps.tuples.isEmpty())
            assertTrue(deps.modes.isEmpty())
            vm.close()
        }
    }
    test("empty catalog and absent capabilities retain effort and default tier without correction") {
        runTest {
            val deps = RuntimePorts()
            val vm = DefaultRuntimeConfigurationViewModelFactory.create(deps, backgroundScope)
            runCurrent()
            assertEquals(
                RuntimeConfigurationModelOption(
                    OpenAiModelId("current"), listOf(ReasoningEffort.High), listOf(ServiceTier.Default),
                ),
                vm.state.value.modelOptions.single(),
            )
            deps.models.value = listOf(info("current", emptyList()))
            deps.configuration.value = deps.configuration.value.copy(
                reasoning = ReasoningEffort.Custom("unknown"), tier = ServiceTier.Fast,
            )
            runCurrent()
            assertEquals(deps.configuration.value, vm.state.value.configuration)
            assertEquals(listOf(ReasoningEffort.Custom("unknown")), vm.state.value.modelOptions.single().efforts)
            assertEquals(listOf(ServiceTier.Default), vm.state.value.modelOptions.single().tiers)
            assertTrue(deps.tuples.isEmpty())
            vm.close()
        }
    }
    test("all canonical tiers derive from availableServiceTiers including unknown metadata") {
        runTest {
            val deps = RuntimePorts()
            deps.models.value = listOf(
                info("current", listOf(ReasoningEffort.Max), ServiceTier.Flex, ServiceTier.Fast).copy(
                    serviceTiers = listOf(
                        ModelServiceTier("future-tier", "Future", "Unknown"),
                        ModelServiceTier(ServiceTier.Flex.requestValue, "Flex", ""),
                        ModelServiceTier(ServiceTier.Fast.requestValue, "Fast", ""),
                        ModelServiceTier(ServiceTier.Fast.requestValue, "Duplicate", ""),
                        ModelServiceTier(ServiceTier.Ultrafast.requestValue, "Ultrafast", "Higher usage"),
                    ),
                ),
            )
            val vm = createRuntimeConfigurationViewModel(deps, backgroundScope)
            assertEquals(ServiceTier.entries.toList(), vm.state.value.modelOptions.single().tiers)
            // Saved High is not corrected to the only advertised Max.
            assertEquals(ReasoningEffort.High, vm.state.value.configuration.reasoning)
            vm.close()
        }
    }
    test("atomic tuple once mode separately and state changes only on source receipt") {
        runTest {
            val deps = RuntimePorts()
            val vm = createRuntimeConfigurationViewModel(deps, backgroundScope)
            val baseline = vm.state.value.configuration
            val selected = ModelTuple(OpenAiModelId("outside-catalog"), ReasoningEffort.Custom("special"), ServiceTier.Fast)
            vm.updateModelConfiguration(selected.model, selected.effort, selected.tier)
            vm.updateRequestUserInputMode(RequestUserInputMode.NoQuestion)
            assertEquals(listOf(selected), deps.tuples)
            assertEquals(listOf(RequestUserInputMode.NoQuestion), deps.modes)
            assertEquals(baseline, vm.state.value.configuration)
            // Port receipt retains the exact selected values without a capability-validation rewrite.
            deps.configuration.value = RuntimeConfiguration(
                selected.model, selected.effort, selected.tier, RequestUserInputMode.NoQuestion,
            )
            runCurrent()
            assertEquals(deps.configuration.value, vm.state.value.configuration)
            vm.close()
        }
    }
    test("caller cancellation cancels each command and never creates durable submissions") {
        runTest {
            val deps = RuntimePorts()
            deps.gate = CompletableDeferred()
            val vm = createRuntimeConfigurationViewModel(deps, backgroundScope)
            val tupleWait = async { vm.updateModelConfiguration(OpenAiModelId("chosen"), ReasoningEffort.Max, ServiceTier.Fast) }
            runCurrent()
            assertEquals(1, deps.tuples.size)
            tupleWait.cancel()
            assertFailsWith<CancellationException> { tupleWait.await() }
            runCurrent()
            assertEquals(1, deps.cancelled)
            val modeWait = async { vm.updateRequestUserInputMode(RequestUserInputMode.NoQuestion) }
            runCurrent()
            modeWait.cancel()
            assertFailsWith<CancellationException> { modeWait.await() }
            runCurrent()
            assertEquals(2, deps.cancelled)
            deps.gate!!.complete(Unit)
            runCurrent()
            assertEquals(0, deps.completed)
            assertEquals(1, deps.tuples.size)
            assertEquals(1, deps.modes.size)
            vm.close()
        }
    }
    test("close cancels local waits freezes projection and never closes borrowed owner") {
        runTest {
            val deps = RuntimePorts()
            deps.gate = CompletableDeferred()
            val ownerJob = Job(coroutineContext[Job])
            val vm = createRuntimeConfigurationViewModel(deps, CoroutineScope(coroutineContext + ownerJob))
            val tupleWait = async { vm.updateModelConfiguration(OpenAiModelId("chosen"), ReasoningEffort.Max, ServiceTier.Fast) }
            val modeWait = async { vm.updateRequestUserInputMode(RequestUserInputMode.NoQuestion) }
            runCurrent()
            vm.close()
            vm.close()
            assertFailsWith<CancellationException> { tupleWait.await() }
            assertFailsWith<CancellationException> { modeWait.await() }
            assertTrue(ownerJob.isActive)
            assertFalse(deps.borrowedOwnerClosed)
            assertTrue(vm.state.value.closed)
            val frozen = vm.state.value
            deps.configuration.value = deps.configuration.value.copy(model = OpenAiModelId("later"))
            deps.models.value = listOf(info("later", listOf(ReasoningEffort.Low)))
            vm.updateModelConfiguration(OpenAiModelId("ignored"), ReasoningEffort.None, ServiceTier.Default)
            vm.updateRequestUserInputMode(RequestUserInputMode.AskUser)
            runCurrent()
            assertEquals(frozen, vm.state.value)
            assertEquals(1, deps.tuples.size)
            assertEquals(1, deps.modes.size)
            assertEquals(2, deps.cancelled)
            ownerJob.cancel()
        }
    }
    test("owner cancellation closes observation and cancels caller waits without touching the port") {
        runTest {
            val deps = RuntimePorts()
            deps.gate = CompletableDeferred()
            val owner = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
            val vm = createRuntimeConfigurationViewModel(deps, owner)
            val wait = async { vm.updateRequestUserInputMode(RequestUserInputMode.NoQuestion) }
            runCurrent()
            owner.cancel()
            runCurrent()
            assertFailsWith<CancellationException> { wait.await() }
            assertTrue(vm.state.value.closed)
            assertFalse(deps.borrowedOwnerClosed)
            vm.updateRequestUserInputMode(RequestUserInputMode.AskUser)
            assertEquals(1, deps.modes.size)
            // Construction with an already-cancelled owner is also closed without a write.
            val closed = createRuntimeConfigurationViewModel(deps, owner)
            assertTrue(closed.state.value.closed)
            closed.updateRequestUserInputMode(RequestUserInputMode.AskUser)
            assertEquals(1, deps.modes.size)
        }
    }
    test("delayed tuple stays bound to old owner and target rejection and failures propagate unchanged") {
        runTest {
            val old = RuntimePorts()
            val replacement = RuntimePorts()
            val vm = createRuntimeConfigurationViewModel(old, backgroundScope)
            val replacementVm = createRuntimeConfigurationViewModel(replacement, backgroundScope)
            old.gate = CompletableDeferred()
            val selected = ModelTuple(OpenAiModelId("captured"), ReasoningEffort.Low, ServiceTier.Flex)
            val wait = async { vm.updateModelConfiguration(selected.model, selected.effort, selected.tier) }
            runCurrent()
            old.models.value = listOf(info("replacement-catalog", listOf(ReasoningEffort.Max)))
            old.configuration.value = old.configuration.value.copy(model = OpenAiModelId("fresh"))
            old.gate!!.complete(Unit)
            wait.await()
            assertEquals(listOf(selected), old.tuples)
            assertTrue(replacement.tuples.isEmpty())
            val stale = IllegalStateException("exact binding invalidated")
            old.failure = stale
            assertSame(stale, assertFailsWith<IllegalStateException> {
                vm.updateRequestUserInputMode(RequestUserInputMode.NoQuestion)
            }.originalFailure())
            val failure = UnsupportedOperationException("original failure")
            old.failure = failure
            assertSame(failure, assertFailsWith<UnsupportedOperationException> {
                vm.updateModelConfiguration(selected.model, selected.effort, selected.tier)
            }.originalFailure())
            old.failure = CancellationException("original cancellation")
            assertSame(old.failure, assertFailsWith<CancellationException> {
                vm.updateRequestUserInputMode(RequestUserInputMode.NoQuestion)
            }.originalFailure())
            assertTrue(replacement.modes.isEmpty())
            vm.close()
            replacementVm.close()
        }
    }
}

private data class ModelTuple(val model: OpenAiModelId, val effort: ReasoningEffort, val tier: ServiceTier)

private class RuntimePorts : RuntimeConfigurationDependencies, AutoCloseable {
    override val configuration = MutableStateFlow(
        RuntimeConfiguration(OpenAiModelId("current"), ReasoningEffort.High, ServiceTier.Default, RequestUserInputMode.AskUser),
    )
    override val models = MutableStateFlow(emptyList<ModelInfo>())
    val tuples = mutableListOf<ModelTuple>()
    val modes = mutableListOf<RequestUserInputMode>()
    var gate: CompletableDeferred<Unit>? = null
    var failure: Throwable? = null
    var cancelled = 0
    var completed = 0
    var borrowedOwnerClosed = false
    override suspend fun updateModelConfiguration(model: OpenAiModelId, effort: ReasoningEffort, tier: ServiceTier) {
        failure?.let { throw it }
        tuples += ModelTuple(model, effort, tier)
        waitForReceipt()
    }
    override suspend fun updateRequestUserInputMode(mode: RequestUserInputMode) {
        failure?.let { throw it }
        modes += mode
        waitForReceipt()
    }
    private suspend fun waitForReceipt() {
        try {
            gate?.await()
            completed++
        } catch (cancel: CancellationException) {
            cancelled++
            throw cancel
        }
    }
    override fun close() { borrowedOwnerClosed = true }
}

// Coroutine debug recovery may wrap the same failure to attach the suspended call site.
private fun Throwable.originalFailure(): Throwable = generateSequence(this) { it.cause }.last()

private fun info(name: String, efforts: List<ReasoningEffort>, vararg tiers: ServiceTier): ModelInfo =
    ModelInfo(
        slug = OpenAiModelId(name),
        displayName = name,
        supportedReasoningLevels = efforts.map { ReasoningEffortPreset(it, it.wireName) },
        serviceTiers = tiers.map { ModelServiceTier(it.requestValue, "", "") },
    )
