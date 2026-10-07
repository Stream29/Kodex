package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.hooksettings.*
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.rpc.models.NotificationHook
import io.github.stream29.kodex.rpc.models.NotificationHookType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

val hookSettingsComponentTest by testSuite {
    test("burst Name and Command renderer callbacks preserve both fields before recomposition") {
        val deps = HookRenderPorts()
        withHookVm(deps) { vm ->
            vm.add()
            val updates = mutableListOf<Pair<Int, HookEditorDraft>>()
            val received = CompletableDeferred<Unit>()
            var compositions = 0
            val observed = object : HookSettingsViewModel by vm {
                override fun updateDraft(token: HookDialogToken, update: (HookEditorDraft) -> HookEditorDraft) {
                    val frame = compositions
                    vm.updateDraft(token, update)
                    updates += frame to assertIs<HookSettingsDialog.Editing>(vm.state.value.dialog).draft
                    if (updates.size == 2) received.complete(Unit)
                }
            }
            runMosaicTest {
                try {
                    setContentAndSnapshot {
                        // Frame witness only; the genuine component still owns renderer callbacks.
                        val state by vm.state.collectAsState()
                        assertIs<HookSettingsDialog.Editing>(state.dialog)
                        SideEffect { compositions++ }
                        TuiPopupHost(Modifier.width(100).height(30)) { HookSettingsComponent(observed) }
                    }
                    val initialComposition = compositions
                    sendKeyEvent(KeyboardEvent(codepoint = 'N'.code))
                    repeat(5) { sendKeyEvent(KeyboardEvent(codepoint = 9)) }
                    sendKeyEvent(KeyboardEvent(codepoint = 'C'.code))
                    // No awaitSnapshot/frame between these two genuine TextInput callbacks.
                    // Mosaic drains the entire queued burst before applying this frame.
                    awaitSnapshot()
                    withTimeout(1_000) { received.await() }
                    assertEquals(listOf(initialComposition, initialComposition), updates.map { it.first })
                    val draft = assertIs<HookSettingsDialog.Editing>(vm.state.value.dialog).draft
                    assertEquals("N", draft.name)
                    assertEquals("C", draft.command)
                } finally {
                    cancel()
                }
            }
        }
    }
    test("full renderer consumes VM empty list and application failure acknowledgement") {
        val deps = HookRenderPorts()
        deps.hooks.value = emptyList()
        deps.operationFailure.value = true
        withHookVm(deps) { vm ->
            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    TuiPopupHost(Modifier.width(100).height(30)) { HookSettingsComponent(vm) }
                }
                assertTrue("None configured" in snapshot, snapshot)
                assertTrue("A settings operation failed." in snapshot, snapshot)
                assertTrue("[Dismiss]" in snapshot, snapshot)
                assertTrue("Hooks [Add]" in snapshot, snapshot)
                vm.dismissFailure()
                val acknowledged = awaitHookSnapshot("Hooks [Add]")
                assertFalse("A settings operation failed." in acknowledged, acknowledged)
            }
        }
    }
    test("full renderer displays VM validation instead of creating its own draft") {
        val deps = HookRenderPorts()
        withHookVm(deps) { vm ->
            vm.add()
            val editor = vm.state.value.dialog as HookSettingsDialog.Editing
            vm.save(editor.token)
            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    TuiPopupHost(Modifier.width(100).height(30)) { HookSettingsComponent(vm) }
                }
                assertTrue("Add Hook" in snapshot, snapshot)
                assertTrue("Hook name is required." in snapshot, snapshot)
                sendKeyEvent(KeyboardEvent(codepoint = 'N'.code))
                awaitHookSnapshot("N")
                val draft = (vm.state.value.dialog as HookSettingsDialog.Editing).draft
                assertEquals("N", draft.name)
                assertNull((vm.state.value.dialog as HookSettingsDialog.Editing).error)
                assertTrue(deps.saved.isEmpty())
            }
        }
    }
    test("typing name command and keyboard Save submits the VM draft exactly once") {
        val deps = HookRenderPorts()
        withHookVm(deps) { vm ->
            vm.add()
            runMosaicTest {
                setContentAndSnapshot {
                    TuiPopupHost(Modifier.width(100).height(30)) { HookSettingsComponent(vm) }
                }
                sendKeyEvent(KeyboardEvent(codepoint = 'N'.code))
                awaitHookSnapshot("N")
                // Name -> four type buttons -> Command.
                repeat(5) { sendKeyEvent(KeyboardEvent(codepoint = 9)) }
                sendKeyEvent(KeyboardEvent(codepoint = 'C'.code))
                awaitHookSnapshot("C")
                assertEquals("C", (vm.state.value.dialog as HookSettingsDialog.Editing).draft.command)
                repeat(2) { sendKeyEvent(KeyboardEvent(codepoint = 9)) }
                sendKeyEvent(KeyboardEvent(codepoint = 13))
                awaitHookSnapshot("Hooks [Add]")
                assertEquals("N", deps.saved.single().name)
                assertEquals("C", deps.saved.single().command)
                assertEquals(HookSettingsDialog.Hidden, vm.state.value.dialog)
            }
        }
    }
    test("details and delete branch render exact VM target with cancel first focus") {
        val deps = HookRenderPorts()
        withHookVm(deps) { vm ->
            vm.details("notify")
            runMosaicTest {
                val details = setContentAndSnapshot {
                    TuiPopupHost(Modifier.width(100).height(30)) { HookSettingsComponent(vm) }
                }
                assertTrue("[Close] [Edit] [Delete]" in details, details)
                val token = (vm.state.value.dialog as HookSettingsDialog.Details).token
                vm.requestDelete(token)
                val confirmation = awaitHookSnapshot("Delete 'notify'?")
                assertTrue("[Cancel] [Delete]" in confirmation, confirmation)
                sendKeyEvent(KeyboardEvent(codepoint = 13))
                awaitHookSnapshot("Hooks [Add]")
                assertTrue(deps.deleted.isEmpty())
                assertEquals(HookSettingsDialog.Hidden, vm.state.value.dialog)
            }
        }
    }
    test("delete confirmation keyboard action invokes captured VM command") {
        val deps = HookRenderPorts()
        withHookVm(deps) { vm ->
            vm.details("notify")
            vm.requestDelete((vm.state.value.dialog as HookSettingsDialog.Details).token)
            runMosaicTest {
                setContentAndSnapshot {
                    TuiPopupHost(Modifier.width(100).height(30)) { HookSettingsComponent(vm) }
                }
                sendKeyEvent(KeyboardEvent(codepoint = 9))
                sendKeyEvent(KeyboardEvent(codepoint = 13))
                awaitHookSnapshot("Hooks [Add]")
                assertEquals("notify", deps.deleted.single().name)
            }
        }
    }
    test("replaced editor ignores old callback and Escape discards only current draft") {
        val deps = HookRenderPorts()
        withHookVm(deps) { vm ->
            vm.add()
            val old = (vm.state.value.dialog as HookSettingsDialog.Editing).token
            vm.add()
            val next = (vm.state.value.dialog as HookSettingsDialog.Editing).token
            vm.updateDraft(next) { HookEditorDraft("replacement", "command") }
            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    TuiPopupHost(Modifier.width(100).height(30)) { HookSettingsComponent(vm) }
                }
                assertTrue("replacement" in snapshot, snapshot)
                vm.save(old)
                vm.dismiss(old)
                assertSame(next, (vm.state.value.dialog as HookSettingsDialog.Editing).token)
                sendKeyEvent(KeyboardEvent(codepoint = 27))
                awaitHookSnapshot("Hooks [Add]")
                assertTrue(deps.saved.isEmpty())
                assertEquals(HookSettingsDialog.Hidden, vm.state.value.dialog)
            }
        }
    }
}

private suspend fun withHookVm(deps: HookRenderPorts, action: suspend (HookSettingsViewModel) -> Unit) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val vm = createHookSettingsViewModel(deps, scope)
    try { action(vm) } finally { vm.close(); scope.cancel() }
}

private suspend fun TestMosaic<String>.awaitHookSnapshot(expected: String): String {
    var latest = ""
    repeat(5) {
        latest = try { awaitSnapshot() } catch (_: TimeoutCancellationException) {
            draw().render(AnsiLevel.NONE, supportsKittyUnderlines = false)
        }
        if (expected in latest) return latest
    }
    assertTrue(expected in latest, latest)
    return latest
}

private class HookRenderPorts : HookSettingsDependencies {
    override val hooks = MutableStateFlow(listOf(NotificationHook(
        "notify", setOf(NotificationHookType.StopAssistantMessage), "notify-command",
    )))
    override val operationFailure = MutableStateFlow(false)
    val saved = mutableListOf<NotificationHook>()
    val deleted = mutableListOf<NotificationHook>()
    override fun captureEditor(name: String?): HookEditHandle = object : HookEditHandle {
        override val original = hooks.value.find { it.name == name }
        override fun save(updated: NotificationHook): HookWriteAdmission {
            saved += updated
            return HookWriteAdmission.Accepted
        }
        override fun release() = Unit
    }
    override fun delete(original: NotificationHook): HookWriteAdmission {
        deleted += original
        return HookWriteAdmission.Accepted
    }
    override fun reportFailure(failure: Throwable) { operationFailure.value = true }
    override fun dismissFailure() { operationFailure.value = false }
}
