@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.app.contextsourcesettings

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentcontext.contract.AgentContextCustomSource
import io.github.stream29.kodex.agentcontext.contract.AgentContextSourceSettings
import io.github.stream29.kodex.app.settings.contract.BuiltInContextSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

val contextSourceSettingsViewModelTest by testSuite {
    test("invalid paths and static duplicates retain VM draft without admission") {
        runTest {
            val deps = ContextPorts()
            val vm = createContextSourceSettingsViewModel(deps, backgroundScope)
            vm.add()
            val token = vm.editor().token
            for (input in listOf("", " ", "relative/path", "\$HOME/x")) {
                vm.updateDraft(token, input)
                vm.save(token)
                assertEquals("Enter an absolute path, ~, or ~/path.", vm.editor().error)
                assertEquals(input, vm.editor().draft)
            }
            for (input in listOf("~/.agents", "/home/test/.agents", "~/.kodex", "~/.codex")) {
                vm.updateDraft(token, input)
                assertNull(vm.editor().error)
                vm.save(token)
                assertEquals("This path is already a built-in context source.", vm.editor().error)
            }
            assertTrue(deps.lists.isEmpty())
            assertTrue(deps.failures.isEmpty())
            vm.close()
        }
    }
    test("accepted absolute tilde and home spelling remain trimmed and frozen after close") {
        runTest {
            for (input in listOf("  /absolute/path  ", " ~ ", " ~/custom ")) {
                val deps = ContextPorts()
                val vm = createContextSourceSettingsViewModel(deps, backgroundScope)
                vm.add()
                val token = vm.editor().token
                vm.updateDraft(token, input)
                vm.save(token)
                vm.save(token)
                assertEquals(ContextSourceSettingsDialog.Hidden, vm.state.value.dialog)
                assertEquals(input.trim(), deps.lists.single().second.last().path)
                assertEquals(deps.sources.value, vm.state.value.sources) // admission is not a receipt
                vm.close()
                deps.sources.value = AgentContextSourceSettings()
                deps.drain()
                assertEquals(input.trim(), deps.sources.value.customSources.last().path)
            }
        }
    }
    test("equivalent first duplicate enables in place retaining exact spelling and order") {
        runTest {
            val deps = ContextPorts()
            val original = listOf(
                AgentContextCustomSource("/first"),
                AgentContextCustomSource("~/same", false),
                AgentContextCustomSource("/home/test/same", false),
                AgentContextCustomSource("/last"),
            )
            deps.sources.value = AgentContextSourceSettings(customSources = original)
            val vm = createContextSourceSettingsViewModel(deps, backgroundScope)
            vm.add()
            val token = vm.editor().token
            vm.updateDraft(token, "/home/test/same")
            vm.save(token)
            assertEquals(original, deps.lists.single().first)
            assertEquals(original.mapIndexed { i, s -> if (i == 1) s.copy(enabled = true) else s },
                deps.lists.single().second)
            vm.close()
        }
    }
    test("typed built-in and exact custom commands use current baseline without retargeting") {
        runTest {
            val deps = ContextPorts()
            val vm = createContextSourceSettingsViewModel(deps, backgroundScope)
            // Do not run collectors: commands still read current dependencies.
            val source = AgentContextCustomSource("~/target", false)
            deps.sources.value = deps.sources.value.copy(agentsHomeEnabled = false, customSources = listOf(source))
            vm.setBuiltInEnabled(BuiltInContextSource.AgentsHome, true)
            vm.setCustomEnabled("/home/test/target", true)
            vm.removeCustom("/missing")
            assertTrue(deps.custom.isEmpty())
            vm.setCustomEnabled("~/target", true)
            vm.removeCustom("~/target")
            assertEquals(Triple(BuiltInContextSource.AgentsHome, false, true), deps.builtIns.single())
            assertSame(source, deps.custom.single().first)
            assertSame(source, deps.removed.single())
            vm.close()
        }
    }
    test("replacement hide dismissal and late callbacks cannot consume a newer dialog") {
        runTest {
            val deps = ContextPorts()
            val vm = createContextSourceSettingsViewModel(deps, backgroundScope)
            vm.add()
            val old = vm.editor().token
            vm.add()
            val next = vm.editor().token
            vm.updateDraft(old, "/late")
            vm.save(old)
            vm.dismiss(old)
            assertSame(next, vm.editor().token)
            assertEquals("", vm.editor().draft)
            vm.updateDraft(next, "/draft")
            vm.hidePage()
            vm.save(next)
            assertEquals(ContextSourceSettingsDialog.Hidden, vm.state.value.dialog)
            assertTrue(deps.lists.isEmpty())
            vm.close()
        }
    }
    test("double reentrant admission is guarded and accepted callback cannot close replacement") {
        runTest {
            val deps = ContextPorts()
            val vm = createContextSourceSettingsViewModel(deps, backgroundScope)
            vm.add()
            val token = vm.editor().token
            vm.updateDraft(token, "/accepted")
            deps.onWrite = {
                vm.save(token)
                vm.add()
            }
            vm.save(token)
            assertEquals(1, deps.lists.size)
            assertNotSame(token, vm.editor().token)
            assertEquals("", vm.editor().draft)
            vm.close()
        }
    }
    test("policy replacement prevents any admission and rejection retains only exact draft") {
        runTest {
            val deps = ContextPorts()
            val vm = createContextSourceSettingsViewModel(deps, backgroundScope)
            vm.add()
            val token = vm.editor().token
            vm.updateDraft(token, "/old")
            deps.onNormalize = { vm.add() }
            vm.save(token)
            assertTrue(deps.lists.isEmpty())
            assertNotSame(token, vm.editor().token)
            deps.onNormalize = null
            deps.admission = ContextSourceWriteAdmission.Rejected("Queue unavailable.")
            val next = vm.editor().token
            vm.updateDraft(next, "/new")
            vm.save(next)
            assertEquals("/new", vm.editor().draft)
            assertEquals("Queue unavailable.", vm.editor().error)
            assertTrue(deps.failures.isEmpty())
            vm.close()
        }
    }
    test("environment and admission exceptions report shared authority without leaking details") {
        runTest {
            val deps = ContextPorts()
            val vm = createContextSourceSettingsViewModel(deps, backgroundScope)
            vm.add()
            val token = vm.editor().token
            vm.updateDraft(token, "/valid")
            deps.policyFailure = IllegalStateException("private home path")
            vm.save(token)
            runCurrent()
            assertEquals(1, deps.failures.size)
            assertTrue(vm.state.value.operationFailure)
            assertEquals("The context source could not be queued.", vm.editor().error)
            deps.policyFailure = null
            deps.writeFailure = IllegalStateException("remote secret")
            vm.save(token)
            assertEquals(2, deps.failures.size)
            vm.close()
            val reopened = createContextSourceSettingsViewModel(deps, backgroundScope)
            assertTrue(reopened.state.value.operationFailure)
            reopened.dismissFailure()
            runCurrent()
            assertFalse(reopened.state.value.operationFailure)
            reopened.close()
        }
    }
    test("cancellation is unchanged and owner cancellation closes without cancelling queued writes") {
        runTest {
            val deps = ContextPorts()
            val scope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
            val vm = createContextSourceSettingsViewModel(deps, scope)
            vm.add()
            val token = vm.editor().token
            vm.updateDraft(token, "/valid")
            val cancelled = CancellationException("cancel")
            deps.writeFailure = cancelled
            assertSame(cancelled, assertFailsWith<CancellationException> { vm.save(token) })
            assertTrue(deps.failures.isEmpty())
            deps.writeFailure = null
            vm.save(token)
            runCurrent()
            scope.cancel()
            runCurrent()
            assertTrue(vm.state.value.closed)
            vm.add()
            vm.setBuiltInEnabled(BuiltInContextSource.GitRoot, false)
            vm.setCustomEnabled("/valid", false)
            vm.removeCustom("/valid")
            vm.dismissFailure()
            assertEquals(1, deps.lists.size)
            assertTrue(deps.builtIns.isEmpty())
            deps.drain()
            assertEquals("/valid", deps.sources.value.customSources.last().path)
            vm.close()
        }
    }
    test("policy cancellation retains draft and reports no failure; close during rejection never reopens") {
        runTest {
            val deps = ContextPorts()
            val vm = createContextSourceSettingsViewModel(deps, backgroundScope)
            vm.add()
            val token = vm.editor().token
            vm.updateDraft(token, "/valid")
            val cancelled = CancellationException("environment cancelled")
            deps.policyFailure = cancelled
            assertSame(cancelled, assertFailsWith<CancellationException> { vm.save(token) })
            assertEquals("/valid", vm.editor().draft)
            assertTrue(deps.failures.isEmpty())
            deps.policyFailure = null
            deps.onWrite = { vm.close() }
            deps.admission = ContextSourceWriteAdmission.Rejected("Closed.")
            vm.save(token)
            assertTrue(vm.state.value.closed)
            assertEquals(ContextSourceSettingsDialog.Hidden, vm.state.value.dialog)
            assertTrue(deps.lists.isEmpty())
        }
    }
    test("list rejection reports shared failure once and source observation never edits settings") {
        runTest {
            val deps = ContextPorts()
            val vm = createContextSourceSettingsViewModel(deps, backgroundScope)
            runCurrent()
            deps.admission = ContextSourceWriteAdmission.Rejected("No edits accepted.")
            vm.setBuiltInEnabled(BuiltInContextSource.GitRoot, false)
            runCurrent()
            assertEquals(1, deps.failures.size)
            assertTrue(vm.state.value.operationFailure)
            assertTrue(deps.builtIns.isEmpty())
            deps.sources.value = deps.sources.value.copy(customSources = listOf(AgentContextCustomSource("/observed")))
            runCurrent()
            assertEquals("/observed", vm.state.value.sources.customSources.single().path)
            assertTrue(deps.lists.isEmpty())
            vm.close()
            val frozen = vm.state.value
            deps.sources.value = AgentContextSourceSettings()
            runCurrent()
            assertEquals(frozen, vm.state.value)
        }
    }
}

private fun ContextSourceSettingsViewModel.editor(): ContextSourceSettingsDialog.Adding =
    state.value.dialog as ContextSourceSettingsDialog.Adding

private class ContextPorts : ContextSourceSettingsDependencies {
    override val sources = MutableStateFlow(AgentContextSourceSettings())
    override val operationFailure = MutableStateFlow(false)
    var policyFailure: Throwable? = null
    var writeFailure: Throwable? = null
    var onNormalize: (() -> Unit)? = null
    var onWrite: (() -> Unit)? = null
    var admission: ContextSourceWriteAdmission = ContextSourceWriteAdmission.Accepted
    val lists = mutableListOf<Pair<List<AgentContextCustomSource>, List<AgentContextCustomSource>>>()
    val builtIns = mutableListOf<Triple<BuiltInContextSource, Boolean, Boolean>>()
    val custom = mutableListOf<Pair<AgentContextCustomSource, Boolean>>()
    val removed = mutableListOf<AgentContextCustomSource>()
    val failures = mutableListOf<Throwable>()
    override val pathPolicy = object : ContextSourcePathPolicy {
        override val builtInNormalizedPaths = setOf("/home/test/.agents", "/home/test/.kodex", "/home/test/.codex")
        override fun normalize(path: String): String? {
            policyFailure?.let { throw it }
            onNormalize?.invoke()
            return when {
                path.isBlank() || '$' in path -> null
                path == "~" -> "/home/test"
                path.startsWith("~/") -> "/home/test/" + path.substring(2)
                path.startsWith("/") -> path
                else -> null
            }
        }
    }
    private fun accept(action: () -> Unit): ContextSourceWriteAdmission {
        onWrite?.invoke()
        writeFailure?.let { throw it }
        if (admission == ContextSourceWriteAdmission.Accepted) action()
        return admission
    }
    override fun setBuiltInEnabled(source: BuiltInContextSource, expected: Boolean, enabled: Boolean) =
        accept { builtIns += Triple(source, expected, enabled) }
    override fun replaceCustomSources(expected: List<AgentContextCustomSource>, updated: List<AgentContextCustomSource>) =
        accept { lists += expected.toList() to updated.toList() }
    override fun setCustomEnabled(original: AgentContextCustomSource, enabled: Boolean) =
        accept { custom += original to enabled }
    override fun removeCustom(original: AgentContextCustomSource) = accept { removed += original }
    override fun reportFailure(failure: Throwable) { failures += failure; operationFailure.value = true }
    override fun dismissFailure() { operationFailure.value = false }
    fun drain() {
        lists.forEach { (_, updated) -> sources.value = sources.value.copy(customSources = updated) }
    }
}
