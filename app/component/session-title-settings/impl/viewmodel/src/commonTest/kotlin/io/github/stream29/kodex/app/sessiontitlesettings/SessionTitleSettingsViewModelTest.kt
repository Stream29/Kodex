@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.app.sessiontitlesettings

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.cli.settings.SessionTitleSettings
import io.github.stream29.kodex.openai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

val sessionTitleSettingsViewModelTest by testSuite {
    test("default is injected and options keep first catalog occurrence then missing effective") {
        runTest {
            val deps = TitlePorts()
            val first = OpenAiModelId("first")
            deps.models.value = listOf(ModelInfo(first, "first"), ModelInfo(first, "duplicate"))
            val vm = createSessionTitleSettingsViewModel(deps, backgroundScope)
            assertNull(vm.state.value.configuredModel)
            assertEquals(deps.defaultModel, vm.state.value.effectiveModel)
            assertEquals(listOf(first, deps.defaultModel), vm.state.value.modelOptions)
            runCurrent()
            val unavailable = OpenAiModelId("unavailable")
            deps.settings.value = deps.settings.value.copy(model = unavailable)
            deps.models.value = emptyList()
            runCurrent()
            assertEquals(unavailable, vm.state.value.configuredModel)
            assertEquals(listOf(unavailable), vm.state.value.modelOptions)
            deps.models.value = listOf(ModelInfo(first, "first"), ModelInfo(unavailable, "current"), ModelInfo(first, "dup"))
            runCurrent()
            assertEquals(listOf(first, unavailable), vm.state.value.modelOptions)
            assertTrue(deps.modelsWritten.isEmpty())
            assertTrue(deps.enabledWritten.isEmpty())
            vm.close()
        }
    }
    test("fixed reasoning options do not depend on model support or auto-correct custom effort") {
        runTest {
            val deps = TitlePorts()
            val custom = ReasoningEffort.Custom("provider-effort")
            deps.settings.value = deps.settings.value.copy(reasoningEffort = custom)
            val vm = createSessionTitleSettingsViewModel(deps, backgroundScope)
            assertEquals(custom, vm.state.value.reasoningEffort)
            assertEquals(listOf(ReasoningEffort.None, ReasoningEffort.Minimal, ReasoningEffort.Low,
                ReasoningEffort.Medium, ReasoningEffort.High, ReasoningEffort.XHigh, ReasoningEffort.Max),
                vm.state.value.reasoningOptions)
            assertTrue(deps.reasoningWritten.isEmpty())
            vm.close()
        }
    }
    test("disabled controls do not reject public commands and baselines use current field") {
        runTest {
            val deps = TitlePorts()
            deps.settings.value = SessionTitleSettings(enabled = false)
            val vm = createSessionTitleSettingsViewModel(deps, backgroundScope)
            val original = OpenAiModelId("original")
            val updated = OpenAiModelId("updated")
            deps.settings.value = deps.settings.value.copy(model = original, reasoningEffort = ReasoningEffort.High)
            vm.setModel(updated)
            vm.setReasoningEffort(ReasoningEffort.Max)
            vm.setEnabled(true)
            assertEquals(original to updated, deps.modelsWritten.single())
            assertEquals(ReasoningEffort.High to ReasoningEffort.Max, deps.reasoningWritten.single())
            assertEquals(false to true, deps.enabledWritten.single())
            assertFalse(vm.state.value.enabled) // no synthetic persistence receipt
            vm.setModel(null)
            assertEquals(original to null, deps.modelsWritten.last())
            vm.hidePage()
            vm.close()
            deps.drain()
            assertTrue(deps.settings.value.enabled)
            assertNull(deps.settings.value.model)
            assertEquals(ReasoningEffort.Max, deps.settings.value.reasoningEffort)
        }
    }
    test("rejection and unexpected exception report once and shared failure survives reopen") {
        runTest {
            val deps = TitlePorts()
            val vm = createSessionTitleSettingsViewModel(deps, backgroundScope)
            deps.admission = SessionTitleWriteAdmission.Rejected
            vm.setEnabled(false)
            assertEquals(1, deps.failures.size)
            deps.failure = IllegalStateException("remote details")
            vm.setModel(OpenAiModelId("new"))
            assertEquals(2, deps.failures.size)
            runCurrent()
            assertTrue(vm.state.value.operationFailure)
            assertTrue(deps.enabledWritten.isEmpty())
            vm.close()
            val next = createSessionTitleSettingsViewModel(deps, backgroundScope)
            assertTrue(next.state.value.operationFailure)
            next.dismissFailure()
            runCurrent()
            assertFalse(next.state.value.operationFailure)
            next.close()
        }
    }
    test("cancel propagates unchanged; owner cancel and close reject commands and freeze projection") {
        runTest {
            val deps = TitlePorts()
            val owner = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
            val vm = createSessionTitleSettingsViewModel(deps, owner)
            val cancelled = CancellationException("cancel")
            deps.failure = cancelled
            assertSame(cancelled, assertFailsWith<CancellationException> { vm.setEnabled(false) })
            assertTrue(deps.failures.isEmpty())
            deps.failure = null
            vm.setEnabled(false)
            runCurrent()
            owner.cancel()
            runCurrent()
            assertTrue(vm.state.value.closed)
            val frozen = vm.state.value
            deps.settings.value = SessionTitleSettings(model = OpenAiModelId("later"))
            vm.setEnabled(true)
            vm.setModel(null)
            vm.setReasoningEffort(ReasoningEffort.Low)
            vm.dismissFailure()
            runCurrent()
            assertEquals(frozen, vm.state.value)
            assertEquals(1, deps.enabledWritten.size)
            assertTrue(deps.modelsWritten.isEmpty())
            vm.close()
            deps.drain()
            assertFalse(deps.settings.value.enabled)
            assertEquals(OpenAiModelId("later"), deps.settings.value.model) // field writes merge
        }
    }
}

private class TitlePorts : SessionTitleSettingsDependencies {
    override val settings = MutableStateFlow(SessionTitleSettings())
    override val models = MutableStateFlow(emptyList<ModelInfo>())
    override val defaultModel = OpenAiModelId("injected-test-default")
    override val operationFailure = MutableStateFlow(false)
    val enabledWritten = mutableListOf<Pair<Boolean, Boolean>>()
    val modelsWritten = mutableListOf<Pair<OpenAiModelId?, OpenAiModelId?>>()
    val reasoningWritten = mutableListOf<Pair<ReasoningEffort, ReasoningEffort>>()
    val failures = mutableListOf<Throwable>()
    private val queue = mutableListOf<() -> Unit>()
    var admission = SessionTitleWriteAdmission.Accepted
    var failure: Throwable? = null
    private fun admit(action: () -> Unit): SessionTitleWriteAdmission {
        failure?.let { throw it }
        if (admission == SessionTitleWriteAdmission.Accepted) action()
        return admission
    }
    override fun setEnabled(expected: Boolean, enabled: Boolean) = admit {
        enabledWritten += expected to enabled
        queue += { settings.value = settings.value.copy(enabled = enabled) }
    }
    override fun setModel(expected: OpenAiModelId?, model: OpenAiModelId?) = admit {
        modelsWritten += expected to model
        queue += { settings.value = settings.value.copy(model = model) }
    }
    override fun setReasoningEffort(expected: ReasoningEffort, reasoningEffort: ReasoningEffort) = admit {
        reasoningWritten += expected to reasoningEffort
        queue += { settings.value = settings.value.copy(reasoningEffort = reasoningEffort) }
    }
    override fun reportFailure(failure: Throwable) { failures += failure; operationFailure.value = true }
    override fun dismissFailure() { operationFailure.value = false }
    fun drain() { queue.toList().forEach { it() }; queue.clear() }
}
