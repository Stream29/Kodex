package io.github.stream29.kodex.app.settings

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.settings.contract.*
import io.github.stream29.kodex.app.test.*
import io.github.stream29.kodex.cli.rpc.*
import io.github.stream29.kodex.cli.settings.*
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.rpc.models.*
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runCurrent
import kotlin.test.*

/** Backend observations/CAS/auth/reset are exercised in RpcSettingsTest and BackendAccountStateTest. */
@OptIn(ExperimentalCoroutinesApi::class)
val settingsViewModelTest by testSuite {
    test("update queue drains accepted writes before closing its target") {
        val release = CompletableDeferred<Unit>()
        var written = false
        var closed = false
        val queue = SettingsUpdateQueue(testScope.backgroundScope)
        queue.submit { release.await(); written = true }
        testScope.runCurrent()
        queue.close { closed = true }
        assertFalse(closed)
        release.complete(Unit)
        testScope.runCurrent()
        assertTrue(written)
        assertTrue(closed)
    }
    test("update queue continues after a failed write") {
        var written = false
        var reported = false
        val queue = SettingsUpdateQueue(testScope.backgroundScope)
        queue.submit(reportError = { reported = true }) { error("write failed") }
        queue.submit { written = true }
        testScope.runCurrent()
        assertTrue(reported)
        assertTrue(written)
        queue.close {}
    }
    test("settings root observes independent authorities and closes only its children") {
        withRpcFrontend {
            val global = RpcGlobalSettings.open(services.global, openCliFrontendSettings(root), this, 120)
            val view = views.open(create("target").sessionIndex)
            val editor = RpcGlobalEditor(global, services.global, this)
            val session = createSessionSettingsViewModel(RpcSessionSettingsSource(view, this), models, this)
            val vm = createSettingsViewModel(SettingsPage.General, editor, session, RpcNewSessionSettings(global, this))
            try {
                vm.global.updateNewLineKey(NewLineKey.Enter)
                vm.global.updateSessionTitleEnabled(true)
                editor.state.first { it.newLineKey == NewLineKey.Enter && it.sessionTitle.enabled }
                val available = assertIs<SessionSettingsState.Available>(session.state.value)
                session.updateReasoningEffort(available.snapshot.revision, ReasoningEffort.High)
                view.current().settings.first { it.reasoning.effort == ReasoningEffort.High }
                vm.selectPage(SettingsPage.CurrentSession)
                assertEquals(SettingsPage.CurrentSession, vm.selectedPage.value)
                vm.close()
                editor.updateNewLineKey(NewLineKey.ShiftEnter)
                assertEquals(NewLineKey.Enter, global.frontend.settings.value.newLineKey)
                assertEquals("target", view.current().settings.value.threadName)
                assertNotNull(services.global.getSettings())
            } finally { vm.close(); global.close(); global.join() }
        }
    }
    test("session settings rejects stale revisions and never retargets") {
        withRpcFrontend {
            val a = create("A")
            val b = create("B")
            val view = views.open(a.sessionIndex)
            val editor = createSessionSettingsViewModel(RpcSessionSettingsSource(view, this), models, this)
            try {
                val revision = assertIs<SessionSettingsState.Available>(editor.state.value).snapshot.revision
                editor.renameSession(revision, "renamed")
                editor.state.first { (it as? SessionSettingsState.Available)?.snapshot?.sessionName == "renamed" }
                editor.renameSession(revision, "stale")
                assertEquals("B", b.settings.value.threadName)
                assertEquals("renamed", view.current().settings.value.threadName)
                sessions.release(a.sessionIndex)
                editor.state.first { it == SessionSettingsState.Unavailable }
                editor.renameSession(revision, "closed")
            } finally { editor.close() }
        }
    }
    test("context sources and notification hooks use their respective stores") {
        withRpcFrontend {
            val global = RpcGlobalSettings.open(services.global, openCliFrontendSettings(root), this, 120)
            val editor = RpcGlobalEditor(global, services.global, this)
            try {
                val contextPath = SystemCoroutineFileSystem.resolve(root).toString()
                editor.setBuiltInContextSourceEnabled(BuiltInContextSource.WorkingDirectory, false)
                assertNull(editor.addCustomContextSource(contextPath))
                global.settings.first { !it.contextSources.workingDirectoryEnabled && it.contextSources.customSources.size == 1 }
                val hook = NotificationHook("notify", NotificationHookType.entries.toSet(), "echo test")
                val child = editor.hookSettings
                child.add()
                val adding = assertIs<io.github.stream29.kodex.app.hooksettings.HookSettingsDialog.Editing>(child.state.value.dialog)
                child.updateDraft(adding.token, io.github.stream29.kodex.app.hooksettings.HookEditorDraft(
                    hook.name, hook.command, hook.types,
                ))
                child.save(adding.token)
                child.state.first { it.hooks == listOf(hook) }
                child.details("notify")
                val details = assertIs<io.github.stream29.kodex.app.hooksettings.HookSettingsDialog.Details>(child.state.value.dialog)
                child.requestDelete(details.token)
                val staleDelete = assertIs<io.github.stream29.kodex.app.hooksettings.HookSettingsDialog.Deleting>(child.state.value.dialog)
                child.details("notify")
                val reopened = assertIs<io.github.stream29.kodex.app.hooksettings.HookSettingsDialog.Details>(child.state.value.dialog)
                child.edit(reopened.token)
                val editing = assertIs<io.github.stream29.kodex.app.hooksettings.HookSettingsDialog.Editing>(child.state.value.dialog)
                assertEquals(hook.command, editing.draft.command)
                child.updateDraft(editing.token, editing.draft.copy(command = "echo changed"))
                child.save(editing.token)
                child.state.first { it.hooks.single().command == "echo changed" }
                child.confirmDelete(staleDelete.token)
                assertEquals(1, child.state.value.hooks.size)
                child.details("notify")
                val current = assertIs<io.github.stream29.kodex.app.hooksettings.HookSettingsDialog.Details>(child.state.value.dialog)
                child.requestDelete(current.token)
                val deletion = assertIs<io.github.stream29.kodex.app.hooksettings.HookSettingsDialog.Deleting>(child.state.value.dialog)
                child.confirmDelete(deletion.token)
                child.state.first { it.hooks.isEmpty() }
                editor.removeCustomContextSource(contextPath)
                global.settings.first { it.contextSources.customSources.isEmpty() }
            } finally { editor.close(); global.close(); global.join() }
        }
    }
}
