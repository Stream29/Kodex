@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.app.hooksettings

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.rpc.models.NotificationHook
import io.github.stream29.kodex.rpc.models.NotificationHookType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

val hookSettingsViewModelTest by testSuite {
    test("initial ordered list and empty list require no capture or write") {
        runTest {
            val deps = HookPorts()
            val vm = createHookSettingsViewModel(deps, backgroundScope)
            assertEquals(deps.hooks.value, vm.state.value.hooks)
            assertEquals(HookSettingsDialog.Hidden, vm.state.value.dialog)
            runCurrent()
            deps.hooks.value = emptyList()
            runCurrent()
            assertTrue(vm.state.value.hooks.isEmpty())
            assertEquals(0, deps.handles.size)
            assertTrue(deps.accepted.isEmpty())
            vm.close()
        }
    }
    test("name trim command preservation and exactly once queue admission") {
        runTest {
            val deps = HookPorts()
            val vm = createHookSettingsViewModel(deps, backgroundScope)
            vm.add()
            val editor = vm.editor()
            assertEquals(NotificationHookType.entries.toSet(), editor.draft.types)
            vm.updateDraft(editor.token, editor.draft.copy(name = "  added  ", command = "  echo notify  "))
            vm.save(editor.token)
            vm.save(editor.token)
            assertEquals("added", deps.accepted.single().second.name)
            assertEquals("  echo notify  ", deps.accepted.single().second.command)
            assertNull(deps.accepted.single().first)
            assertEquals(1, deps.handles.single().releases)
            assertEquals(HookSettingsDialog.Hidden, vm.state.value.dialog)
            // A successful admission is not a synthetic persisted list update.
            assertFalse(vm.state.value.hooks.any { it.name == "added" })
            vm.close()
            assertEquals(1, deps.accepted.size)
        }
    }
    test("blank name command and empty types retain business draft and display validation") {
        runTest {
            val deps = HookPorts()
            val vm = createHookSettingsViewModel(deps, backgroundScope)
            vm.add()
            val token = vm.editor().token
            vm.save(token)
            assertEquals("Hook name is required.", vm.editor().error)
            vm.updateDraft(token, HookEditorDraft(name = "name"))
            assertNull(vm.editor().error)
            vm.save(token)
            assertEquals("Command is required.", vm.editor().error)
            vm.updateDraft(token, HookEditorDraft("name", "echo", emptySet()))
            vm.save(token)
            assertNotNull(vm.editor().error)
            assertTrue(vm.editor().draft.types.isEmpty())
            assertTrue(deps.accepted.isEmpty())
            vm.close()
        }
    }
    test("editing keeps opening baseline while details follows latest named value") {
        runTest {
            val deps = HookPorts()
            val vm = createHookSettingsViewModel(deps, backgroundScope)
            runCurrent()
            vm.details("existing")
            val original = deps.hooks.value.single()
            deps.hooks.value = listOf(original.copy(command = "new command"))
            runCurrent()
            val details = vm.state.value.dialog as HookSettingsDialog.Details
            assertEquals("new command", details.hook.command)
            vm.edit(details.token)
            val editor = vm.editor()
            val captured = deps.handles.single().original
            deps.hooks.value = listOf(original.copy(command = "third command"))
            runCurrent()
            vm.updateDraft(editor.token, editor.draft.copy(name = "renamed"))
            vm.save(editor.token)
            assertSame(captured, deps.accepted.single().first)
            assertEquals("new command", deps.accepted.single().second.command)
            assertEquals("renamed", deps.accepted.single().second.name)
            vm.close()
        }
    }
    test("known duplicate name or stale baseline rejection keeps exact editor for retry") {
        runTest {
            val deps = HookPorts()
            deps.admission = HookWriteAdmission.Rejected("The Hook name is no longer available.")
            val vm = createHookSettingsViewModel(deps, backgroundScope)
            vm.add()
            val editor = vm.editor()
            vm.updateDraft(editor.token, HookEditorDraft("existing", "echo"))
            vm.save(editor.token)
            assertSame(editor.token, vm.editor().token)
            assertEquals("The Hook name is no longer available.", vm.editor().error)
            assertEquals(0, deps.handles.single().releases)
            assertTrue(deps.failures.isEmpty())
            vm.close()
        }
    }
    test("deletion uses confirmation captured original and ignores repeat confirmation") {
        runTest {
            val deps = HookPorts()
            val vm = createHookSettingsViewModel(deps, backgroundScope)
            runCurrent()
            vm.details("existing")
            vm.requestDelete((vm.state.value.dialog as HookSettingsDialog.Details).token)
            val deletion = vm.state.value.dialog as HookSettingsDialog.Deleting
            deps.hooks.value = listOf(deletion.original.copy(command = "replacement"))
            runCurrent()
            vm.confirmDelete(deletion.token)
            vm.confirmDelete(deletion.token)
            assertSame(deletion.original, deps.deleted.single())
            assertEquals(HookSettingsDialog.Hidden, vm.state.value.dialog)
            vm.close()
        }
    }
    test("cancel replacement hide and late callback never affect newer editor") {
        runTest {
            val deps = HookPorts()
            val vm = createHookSettingsViewModel(deps, backgroundScope)
            vm.add()
            val old = vm.editor()
            vm.add()
            val next = vm.editor()
            vm.updateDraft(old.token, HookEditorDraft("late", "late"))
            vm.save(old.token)
            vm.dismiss(old.token)
            assertSame(next.token, vm.editor().token)
            assertEquals("", vm.editor().draft.name)
            assertEquals(1, deps.handles.first().releases)
            vm.hidePage()
            vm.save(next.token)
            assertEquals(1, deps.handles.last().releases)
            assertTrue(deps.accepted.isEmpty())
            vm.close()
        }
    }
    test("missing details target disappears without operating on stale data") {
        runTest {
            val deps = HookPorts()
            val vm = createHookSettingsViewModel(deps, backgroundScope)
            runCurrent()
            vm.details("missing")
            assertEquals(HookSettingsDialog.Hidden, vm.state.value.dialog)
            vm.details("existing")
            val token = (vm.state.value.dialog as HookSettingsDialog.Details).token
            deps.hooks.value = emptyList()
            runCurrent()
            vm.edit(token)
            vm.requestDelete(token)
            assertEquals(HookSettingsDialog.Hidden, vm.state.value.dialog)
            assertTrue(deps.handles.isEmpty())
            vm.close()
        }
    }
    test("unknown admission reports once generically and failure survives reopening") {
        runTest {
            val deps = HookPorts()
            deps.failure = IllegalStateException("private path and remote details")
            val vm = createHookSettingsViewModel(deps, backgroundScope)
            vm.add()
            val token = vm.editor().token
            vm.updateDraft(token, HookEditorDraft("name", "echo"))
            vm.save(token)
            runCurrent()
            assertEquals(1, deps.failures.size)
            assertEquals("The Hook configuration could not be queued.", vm.editor().error)
            assertTrue(vm.state.value.operationFailure)
            vm.close()
            val reopened = createHookSettingsViewModel(deps, backgroundScope)
            assertTrue(reopened.state.value.operationFailure)
            reopened.dismissFailure()
            runCurrent()
            assertFalse(reopened.state.value.operationFailure)
            reopened.close()
        }
    }
    test("cancellation propagates unchanged without application failure") {
        runTest {
            val deps = HookPorts()
            val cancelled = CancellationException("cancel")
            deps.failure = cancelled
            val vm = createHookSettingsViewModel(deps, backgroundScope)
            vm.add()
            val token = vm.editor().token
            vm.updateDraft(token, HookEditorDraft("name", "echo"))
            assertSame(cancelled, assertFailsWith<CancellationException> { vm.save(token) })
            assertTrue(deps.failures.isEmpty())
            assertNotNull(vm.editor())
            vm.close()
        }
    }
    test("owner cancellation and close release only local handles and reject new commands") {
        runTest {
            val deps = HookPorts()
            val owner = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
            val vm = createHookSettingsViewModel(deps, owner)
            vm.add()
            val token = vm.editor().token
            runCurrent()
            owner.cancel()
            runCurrent()
            vm.close()
            vm.save(token)
            vm.add()
            vm.details("existing")
            assertTrue(vm.state.value.closed)
            assertEquals(HookSettingsDialog.Hidden, vm.state.value.dialog)
            assertEquals(1, deps.handles.single().releases)
            assertTrue(deps.accepted.isEmpty())
        }
    }
    test("dependency closing during admission cannot reopen the editor on rejection") {
        runTest {
            val deps = HookPorts()
            val vm = createHookSettingsViewModel(deps, backgroundScope)
            vm.add()
            val token = vm.editor().token
            vm.updateDraft(token, HookEditorDraft("name", "command"))
            deps.onWrite = { vm.close() }
            deps.admission = HookWriteAdmission.Rejected("No longer accepting.")
            vm.save(token)
            assertTrue(vm.state.value.closed)
            assertEquals(HookSettingsDialog.Hidden, vm.state.value.dialog)
            assertEquals(1, deps.handles.single().releases)
        }
    }
}

private fun HookSettingsViewModel.editor(): HookSettingsDialog.Editing =
    state.value.dialog as HookSettingsDialog.Editing

private class HookPorts : HookSettingsDependencies {
    override val hooks = MutableStateFlow(listOf(NotificationHook(
        "existing", setOf(NotificationHookType.StopAssistantMessage), "old command",
    )))
    override val operationFailure = MutableStateFlow(false)
    val handles = mutableListOf<Editor>()
    val accepted = mutableListOf<Pair<NotificationHook?, NotificationHook>>()
    val deleted = mutableListOf<NotificationHook>()
    val failures = mutableListOf<Throwable>()
    var admission: HookWriteAdmission = HookWriteAdmission.Accepted
    var failure: Throwable? = null
    var onWrite: (() -> Unit)? = null
    override fun captureEditor(name: String?): HookEditHandle? {
        val original = name?.let { target -> hooks.value.find { it.name == target } ?: return null }
        return Editor(original).also(handles::add)
    }
    inner class Editor(override val original: NotificationHook?) : HookEditHandle {
        var releases = 0
        override fun save(updated: NotificationHook): HookWriteAdmission {
            onWrite?.invoke()
            failure?.let { throw it }
            if (admission == HookWriteAdmission.Accepted) accepted += original to updated
            return admission
        }
        override fun release() { releases++ }
    }
    override fun delete(original: NotificationHook): HookWriteAdmission {
        failure?.let { throw it }
        if (admission == HookWriteAdmission.Accepted) deleted += original
        return admission
    }
    override fun reportFailure(failure: Throwable) { failures += failure; operationFailure.value = true }
    override fun dismissFailure() { operationFailure.value = false }
}
