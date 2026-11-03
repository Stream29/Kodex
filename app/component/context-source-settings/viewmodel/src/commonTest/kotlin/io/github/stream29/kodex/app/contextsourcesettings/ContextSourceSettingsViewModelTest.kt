@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.app.contextsourcesettings

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentcontext.contract.AgentContextCustomSource
import io.github.stream29.kodex.agentcontext.contract.AgentContextSourceSettings
import io.github.stream29.kodex.agentcontext.contract.ContextPathComparison
import io.github.stream29.kodex.agentcontext.contract.ContextPathEvaluation
import io.github.stream29.kodex.agentcontext.contract.ContextPathEvaluationFailure
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
                runCurrent()
                assertEquals("Enter an absolute path, ~, or ~/path.", vm.editor().error)
                assertEquals(input, vm.editor().draft)
            }
            for (input in listOf("~/.agents", "/home/test/.agents", "~/.kodex", "~/.codex")) {
                vm.updateDraft(token, input)
                assertNull(vm.editor().error)
                vm.save(token)
                runCurrent()
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
                runCurrent()
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
            runCurrent()
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
                vm.save(vm.editor().token)
            }
            vm.save(token)
            runCurrent()
            assertEquals(1, deps.lists.size)
            assertEquals(1, deps.evaluations.size)
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
            deps.onEvaluate = { vm.add() }
            vm.save(token)
            runCurrent()
            assertTrue(deps.lists.isEmpty())
            assertNotSame(token, vm.editor().token)
            deps.onEvaluate = null
            deps.admission = ContextSourceWriteAdmission.Rejected("Queue unavailable.")
            val next = vm.editor().token
            vm.updateDraft(next, "/new")
            vm.save(next)
            runCurrent()
            assertEquals("/new", vm.editor().draft)
            assertEquals("Queue unavailable.", vm.editor().error)
            assertTrue(deps.failures.isEmpty())
            deps.admission = ContextSourceWriteAdmission.Accepted
            vm.save(next)
            runCurrent()
            assertEquals("/new", deps.lists.single().second.single().path)
            assertEquals(ContextSourceSettingsDialog.Hidden, vm.state.value.dialog)
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
            runCurrent()
            assertEquals(2, deps.failures.size)
            assertEquals("/valid", vm.editor().draft)
            deps.writeFailure = null
            vm.save(token)
            runCurrent()
            assertEquals("/valid", deps.lists.single().second.single().path)
            assertEquals(ContextSourceSettingsDialog.Hidden, vm.state.value.dialog)
            vm.close()
            val reopened = createContextSourceSettingsViewModel(deps, backgroundScope)
            assertTrue(reopened.state.value.operationFailure)
            reopened.dismissFailure()
            runCurrent()
            assertFalse(reopened.state.value.operationFailure)
            reopened.close()
        }
    }
    test("admission cancellation reports no failure and owner cancellation preserves accepted writes") {
        runTest {
            val deps = ContextPorts()
            val scope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
            val vm = createContextSourceSettingsViewModel(deps, scope)
            vm.add()
            val token = vm.editor().token
            vm.updateDraft(token, "/valid")
            val cancelled = CancellationException("cancel")
            deps.writeFailure = cancelled
            vm.save(token)
            runCurrent()
            assertTrue(deps.failures.isEmpty())
            assertEquals("/valid", vm.editor().draft)
            assertNull(vm.editor().error)
            assertTrue(deps.lists.isEmpty())
            assertSame(cancelled, assertFailsWith<CancellationException> {
                vm.setBuiltInEnabled(BuiltInContextSource.GitRoot, false)
            })
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
            vm.save(token)
            runCurrent()
            assertEquals("/valid", vm.editor().draft)
            assertTrue(deps.failures.isEmpty())
            deps.policyFailure = null
            deps.onWrite = { vm.close() }
            deps.admission = ContextSourceWriteAdmission.Rejected("Closed.")
            vm.save(token)
            runCurrent()
            assertTrue(vm.state.value.closed)
            assertEquals(ContextSourceSettingsDialog.Hidden, vm.state.value.dialog)
            assertTrue(deps.lists.isEmpty())
        }
    }
    test("save freezes trimmed candidate and original spellings before evaluation is dispatched") {
        runTest {
            val deps = ContextPorts()
            val original = listOf(AgentContextCustomSource("~/original", false))
            deps.sources.value = AgentContextSourceSettings(customSources = original)
            val vm = createContextSourceSettingsViewModel(deps, backgroundScope)
            vm.add()
            val token = vm.editor().token
            vm.updateDraft(token, "  /candidate  ")
            vm.save(token)
            deps.sources.value = deps.sources.value.copy(customSources = listOf(AgentContextCustomSource("/new")))
            runCurrent()
            assertEquals("/candidate" to listOf("~/original"), deps.evaluations.single())
            assertEquals("  /candidate  ", vm.editor().draft)
            assertEquals("Context sources changed. Try adding this path again.", vm.editor().error)
            assertTrue(deps.lists.isEmpty())
            vm.close()
        }
    }
    test("editing during await cancels the old evaluation and permits an explicit new save") {
        runTest {
            val deps = ContextPorts()
            val gate = CompletableDeferred<Unit>()
            var cancelled = false
            deps.evaluation = { candidate, paths ->
                try {
                    gate.await()
                    evaluated(candidate, paths)
                } finally {
                    cancelled = !currentCoroutineContext().isActive
                }
            }
            val vm = createContextSourceSettingsViewModel(deps, backgroundScope)
            vm.add()
            val token = vm.editor().token
            vm.updateDraft(token, "/old")
            vm.save(token)
            runCurrent()
            assertEquals(1, deps.evaluations.size)
            vm.updateDraft(token, "  /new  ")
            runCurrent()
            assertTrue(cancelled)
            assertEquals("  /new  ", vm.editor().draft)
            assertNull(vm.editor().error)
            assertTrue(deps.lists.isEmpty())
            assertTrue(deps.failures.isEmpty())
            deps.evaluation = null
            vm.save(token)
            runCurrent()
            assertEquals("/new", deps.lists.single().second.single().path)
            vm.close()
        }
    }
    test("an old noncooperative result cannot admit or clear a newer pending evaluation") {
        runTest {
            val deps = ContextPorts()
            val oldGate = CompletableDeferred<Unit>()
            val nextGate = CompletableDeferred<Unit>()
            var attempts = 0
            deps.evaluation = { candidate, paths ->
                when (++attempts) {
                    1 -> withContext(NonCancellable) { oldGate.await() }
                    2 -> nextGate.await()
                    else -> fail("A pending evaluation must not be submitted twice.")
                }
                evaluated(candidate, paths)
            }
            val vm = createContextSourceSettingsViewModel(deps, backgroundScope)
            vm.add()
            val token = vm.editor().token
            vm.updateDraft(token, "/same")
            vm.save(token)
            runCurrent()
            vm.updateDraft(token, "/changed")
            vm.updateDraft(token, "/same")
            vm.save(token)
            runCurrent()
            assertEquals(2, deps.evaluations.size)
            oldGate.complete(Unit)
            runCurrent()
            assertTrue(deps.lists.isEmpty())
            assertEquals("/same", vm.editor().draft)
            vm.save(token)
            runCurrent()
            assertEquals(2, deps.evaluations.size)
            nextGate.complete(Unit)
            runCurrent()
            assertEquals("/same", deps.lists.single().second.single().path)
            assertTrue(deps.failures.isEmpty())
            vm.close()
        }
    }
    test("dismiss hide replacement close and owner cancellation cancel unaccepted evaluations") {
        runTest {
            for (action in listOf("dismiss", "hide", "replace", "close", "owner")) {
                val deps = ContextPorts()
                val scope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
                val gate = CompletableDeferred<Unit>()
                var cancelled = false
                deps.evaluation = { candidate, paths ->
                    try {
                        gate.await()
                        evaluated(candidate, paths)
                    } finally {
                        cancelled = !currentCoroutineContext().isActive
                    }
                }
                val vm = createContextSourceSettingsViewModel(deps, scope)
                vm.add()
                val token = vm.editor().token
                vm.updateDraft(token, "/unaccepted")
                vm.save(token)
                runCurrent()
                when (action) {
                    "dismiss" -> vm.dismiss(token)
                    "hide" -> vm.hidePage()
                    "replace" -> vm.add()
                    "close" -> vm.close()
                    "owner" -> scope.cancel()
                }
                runCurrent()
                assertTrue(cancelled, action)
                assertTrue(deps.lists.isEmpty(), action)
                assertTrue(deps.failures.isEmpty(), action)
                if (action == "close" || action == "owner") {
                    assertTrue(vm.state.value.closed, action)
                    assertEquals(ContextSourceSettingsDialog.Hidden, vm.state.value.dialog)
                } else {
                    if (action != "replace") vm.add()
                    val next = vm.editor().token
                    assertNotSame(token, next)
                    assertEquals("", vm.editor().draft)
                    deps.evaluation = null
                    vm.updateDraft(next, "/new")
                    vm.save(next)
                    runCurrent()
                    assertEquals("/new", deps.lists.single().second.single().path)
                }
                vm.close()
                scope.cancel()
            }
        }
    }
    test("late result or exception from a closed VM cannot alter a reopened VM or shared failure") {
        runTest {
            for (throws in listOf(false, true)) {
                val deps = ContextPorts()
                val gate = CompletableDeferred<Unit>()
                deps.evaluation = { candidate, paths ->
                    withContext(NonCancellable) { gate.await() }
                    if (throws) throw IllegalStateException("private stale environment")
                    evaluated(candidate, paths)
                }
                val old = createContextSourceSettingsViewModel(deps, backgroundScope)
                old.add()
                val oldToken = old.editor().token
                old.updateDraft(oldToken, "/old")
                old.save(oldToken)
                runCurrent()
                old.close()
                val reopened = createContextSourceSettingsViewModel(deps, backgroundScope)
                reopened.add()
                val next = reopened.editor().token
                reopened.updateDraft(next, "/new draft")
                gate.complete(Unit)
                runCurrent()
                assertEquals("/new draft", reopened.editor().draft)
                assertSame(next, reopened.editor().token)
                assertNull(reopened.editor().error)
                assertFalse(reopened.state.value.operationFailure)
                assertTrue(deps.failures.isEmpty())
                assertTrue(deps.lists.isEmpty())
                deps.evaluation = null
                reopened.save(next)
                runCurrent()
                assertEquals("/new draft", deps.lists.single().second.single().path)
                reopened.close()
            }
        }
    }
    test("baseline changes during await retain draft and require a fresh explicit evaluation") {
        runTest {
            val deps = ContextPorts()
            val original = listOf(AgentContextCustomSource("~/same", false))
            deps.sources.value = AgentContextSourceSettings(customSources = original)
            val gate = CompletableDeferred<Unit>()
            deps.evaluation = { candidate, paths ->
                gate.await()
                evaluated(candidate, paths)
            }
            val vm = createContextSourceSettingsViewModel(deps, backgroundScope)
            vm.add()
            val token = vm.editor().token
            vm.updateDraft(token, " /home/test/same ")
            vm.save(token)
            runCurrent()
            val changed = original + AgentContextCustomSource("/concurrent")
            deps.sources.value = deps.sources.value.copy(customSources = changed)
            gate.complete(Unit)
            runCurrent()
            assertEquals(" /home/test/same ", vm.editor().draft)
            assertEquals("Context sources changed. Try adding this path again.", vm.editor().error)
            assertEquals(1, deps.evaluations.size)
            assertTrue(deps.lists.isEmpty())
            assertTrue(deps.failures.isEmpty())
            deps.evaluation = null
            vm.save(token)
            runCurrent()
            assertEquals(2, deps.evaluations.size)
            assertEquals(changed, deps.lists.single().first)
            assertEquals(listOf(changed[0].copy(enabled = true), changed[1]), deps.lists.single().second)
            vm.close()
        }
    }
    test("unrelated built-in changes during await do not invalidate the custom-list baseline") {
        runTest {
            val deps = ContextPorts()
            val original = listOf(AgentContextCustomSource("/existing"))
            deps.sources.value = AgentContextSourceSettings(customSources = original)
            val gate = CompletableDeferred<Unit>()
            deps.evaluation = { candidate, paths ->
                gate.await()
                evaluated(candidate, paths)
            }
            val vm = createContextSourceSettingsViewModel(deps, backgroundScope)
            vm.add()
            val token = vm.editor().token
            vm.updateDraft(token, "/candidate")
            vm.save(token)
            runCurrent()
            deps.sources.value = deps.sources.value.copy(agentsHomeEnabled = false)
            gate.complete(Unit)
            runCurrent()
            assertEquals(original, deps.lists.single().first)
            assertEquals(original + AgentContextCustomSource("/candidate"), deps.lists.single().second)
            assertFalse(deps.sources.value.agentsHomeEnabled)
            vm.close()
        }
    }
    test("awaited evaluation exceptions preserve exact draft and report once before explicit retry") {
        runTest {
            val deps = ContextPorts()
            val gate = CompletableDeferred<Unit>()
            val failure = IllegalStateException("private backend path")
            deps.evaluation = { _, _ ->
                gate.await()
                throw failure
            }
            val vm = createContextSourceSettingsViewModel(deps, backgroundScope)
            vm.add()
            val token = vm.editor().token
            vm.updateDraft(token, "  ~/retry  ")
            vm.save(token)
            runCurrent()
            assertTrue(deps.failures.isEmpty())
            gate.complete(Unit)
            runCurrent()
            assertSame(failure, deps.failures.single())
            assertSame(token, vm.editor().token)
            assertEquals("  ~/retry  ", vm.editor().draft)
            assertEquals("The context source could not be queued.", vm.editor().error)
            assertTrue(vm.state.value.operationFailure)
            assertTrue(deps.lists.isEmpty())
            deps.evaluation = null
            vm.save(token)
            runCurrent()
            assertEquals("~/retry", deps.lists.single().second.single().path)
            assertEquals(1, deps.failures.size)
            vm.close()
        }
    }
    test("typed evaluation failures and malformed cardinality never admit and remain retryable") {
        runTest {
            for (reason in ContextPathEvaluationFailure.entries) {
                val deps = ContextPorts()
                deps.evaluation = { _, _ -> ContextPathEvaluation.Failure(reason) }
                val vm = createContextSourceSettingsViewModel(deps, backgroundScope)
                vm.add()
                val token = vm.editor().token
                vm.updateDraft(token, "/retry")
                vm.save(token)
                runCurrent()
                assertEquals("/retry", vm.editor().draft)
                assertEquals("The context path could not be evaluated. Try again.", vm.editor().error)
                assertTrue(deps.failures.isEmpty())
                assertTrue(deps.lists.isEmpty())
                deps.evaluation = { _, _ ->
                    ContextPathEvaluation.Evaluated(
                        ContextPathComparison("opaque", false), listOf(ContextPathComparison("extra", false)),
                    )
                }
                vm.save(token)
                runCurrent()
                assertEquals("/retry", vm.editor().draft)
                assertEquals("The context source could not be queued.", vm.editor().error)
                assertEquals(1, deps.failures.size)
                assertTrue(deps.lists.isEmpty())
                deps.evaluation = null
                vm.save(token)
                runCurrent()
                assertEquals("/retry", deps.lists.single().second.single().path)
                vm.close()
            }
        }
    }
    test("backend comparison keys select the first duplicate including invalid legacy rows") {
        runTest {
            val deps = ContextPorts()
            val original = listOf(
                AgentContextCustomSource("legacy relative", false),
                AgentContextCustomSource("C:\\Original\\Spelling", false),
                AgentContextCustomSource("C:/other-spelling", false),
            )
            deps.sources.value = AgentContextSourceSettings(customSources = original)
            deps.evaluation = { candidate, paths ->
                assertEquals("C:/candidate", candidate)
                assertEquals(original.map { it.path }, paths)
                ContextPathEvaluation.Evaluated(
                    ContextPathComparison("backend-only-key", false),
                    listOf(
                        ContextPathComparison(null, false),
                        ContextPathComparison("backend-only-key", false),
                        ContextPathComparison("backend-only-key", false),
                    ),
                )
            }
            val vm = createContextSourceSettingsViewModel(deps, backgroundScope)
            vm.add()
            val token = vm.editor().token
            vm.updateDraft(token, " C:/candidate ")
            vm.save(token)
            runCurrent()
            assertEquals(original, deps.lists.single().first)
            assertEquals(
                listOf(original[0], original[1].copy(enabled = true), original[2]),
                deps.lists.single().second,
            )
            vm.close()
        }
    }
    test("accepted writes survive hide close owner cancellation and component recreation") {
        runTest {
            for (action in listOf("hide", "close", "owner")) {
                val deps = ContextPorts()
                val scope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
                val gate = CompletableDeferred<Unit>()
                deps.evaluation = { candidate, paths ->
                    gate.await()
                    evaluated(candidate, paths)
                }
                val vm = createContextSourceSettingsViewModel(deps, scope)
                vm.add()
                val token = vm.editor().token
                vm.updateDraft(token, "  ~/accepted  ")
                vm.save(token)
                runCurrent()
                assertTrue(deps.lists.isEmpty())
                gate.complete(Unit)
                runCurrent()
                assertEquals(ContextSourceSettingsDialog.Hidden, vm.state.value.dialog)
                assertEquals("~/accepted", deps.lists.single().second.single().path)
                assertTrue(deps.sources.value.customSources.isEmpty())
                vm.save(token)
                vm.dismiss(token)
                when (action) {
                    "hide" -> vm.hidePage()
                    "close" -> vm.close()
                    "owner" -> scope.cancel()
                }
                runCurrent()
                val reopened = createContextSourceSettingsViewModel(deps, backgroundScope)
                reopened.add()
                reopened.updateDraft(reopened.editor().token, "/new draft")
                deps.sources.value = deps.sources.value.copy(gitRootEnabled = false)
                deps.drain()
                runCurrent()
                assertEquals(1, deps.lists.size)
                assertEquals("~/accepted", reopened.state.value.sources.customSources.single().path)
                assertFalse(deps.sources.value.gitRootEnabled)
                assertEquals("/new draft", reopened.editor().draft)
                reopened.close()
                vm.close()
                scope.cancel()
            }
        }
    }
    test("close reentered during synchronous acceptance does not cancel the application's write") {
        runTest {
            val deps = ContextPorts()
            val vm = createContextSourceSettingsViewModel(deps, backgroundScope)
            vm.add()
            val token = vm.editor().token
            vm.updateDraft(token, " ~/accepted ")
            deps.onWrite = { vm.close() }
            vm.save(token)
            runCurrent()
            assertTrue(vm.state.value.closed)
            assertEquals(ContextSourceSettingsDialog.Hidden, vm.state.value.dialog)
            assertEquals("~/accepted", deps.lists.single().second.single().path)
            deps.drain()
            assertEquals("~/accepted", deps.sources.value.customSources.single().path)
            assertTrue(deps.failures.isEmpty())
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
    var onEvaluate: (() -> Unit)? = null
    var evaluation: (suspend (String, List<String>) -> ContextPathEvaluation)? = null
    var onWrite: (() -> Unit)? = null
    var admission: ContextSourceWriteAdmission = ContextSourceWriteAdmission.Accepted
    val lists = mutableListOf<Pair<List<AgentContextCustomSource>, List<AgentContextCustomSource>>>()
    val builtIns = mutableListOf<Triple<BuiltInContextSource, Boolean, Boolean>>()
    val custom = mutableListOf<Pair<AgentContextCustomSource, Boolean>>()
    val removed = mutableListOf<AgentContextCustomSource>()
    val failures = mutableListOf<Throwable>()
    val evaluations = mutableListOf<Pair<String, List<String>>>()
    override val pathPolicy = object : ContextSourcePathPolicy {
        override suspend fun evaluate(candidate: String, originalPaths: List<String>): ContextPathEvaluation {
            evaluations += candidate to originalPaths.toList()
            policyFailure?.let { throw it }
            onEvaluate?.invoke()
            return evaluation?.invoke(candidate, originalPaths) ?: evaluated(candidate, originalPaths)
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

private fun evaluated(candidate: String, originalPaths: List<String> = emptyList()): ContextPathEvaluation.Evaluated {
    fun compare(path: String): ContextPathComparison {
        val key = when {
            path.isBlank() || '$' in path -> null
            path == "~" -> "/home/test"
            path.startsWith("~/") -> "/home/test/" + path.substring(2)
            path.startsWith("/") -> path
            else -> null
        }
        return ContextPathComparison(key, key in setOf("/home/test/.agents", "/home/test/.kodex", "/home/test/.codex"))
    }
    return ContextPathEvaluation.Evaluated(compare(candidate), originalPaths.map { compare(it) })
}
