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
    test("initial OpenAI refresh is single and closed navigation cannot refresh or retarget children") {
        withRpcFrontend {
            val refreshes = MutableStateFlow(0)
            val rpc = object : io.github.stream29.kodex.rpc.contract.GlobalRpc by services.global {
                override suspend fun refreshAccountUsage() { refreshes.value++ }
            }
            val global = RpcGlobalSettings.open(rpc, openCliFrontendSettings(root), this, 120)
            val editor = RpcGlobalEditor(global, rpc, this)
            val view = views.open(create("initial OpenAI").sessionIndex)
            val session = createSessionSettingsViewModel(RpcSessionSettingsSource(view, this), models, this)
            val defaults = RpcNewSessionSettings(global, this)
            val vm = createSettingsViewModel(SettingsPage.OpenAi, editor, session, defaults)
            try {
                refreshes.first { it == 1 }
                vm.selectPage(SettingsPage.OpenAi)
                assertEquals(1, refreshes.value)
                assertSame(editor, vm.global)
                assertSame(session, vm.session)
                assertSame(defaults, vm.newSession)
                vm.close()
                for (page in SettingsPage.entries) vm.selectPage(page)
                vm.close()
                assertEquals(SettingsPage.OpenAi, vm.selectedPage.value)
                assertEquals(1, refreshes.value)
                assertSame(session, vm.session)
                assertEquals("initial OpenAI", view.current().settings.value.threadName)
            } finally { vm.close(); global.close(); global.join() }
        }
    }
    test("close attempts real children in original order and propagates the last disposal failure") {
        withRpcFrontend {
            val global = RpcGlobalSettings.open(services.global, openCliFrontendSettings(root), this, 120)
            val editor = RpcGlobalEditor(global, services.global, this)
            val view = views.open(create("close order").sessionIndex)
            val session = createSessionSettingsViewModel(RpcSessionSettingsSource(view, this), models, this)
            val defaults = RpcNewSessionSettings(global, this)
            val order = mutableListOf<String>()
            val globalFailure = IllegalStateException("global disposal")
            val sessionFailure = CancellationException("session disposal")
            val defaultsFailure = IllegalArgumentException("defaults disposal")
            val vm = createSettingsViewModel(
                SettingsPage.General,
                object : GlobalSettingsViewModel by editor {
                    override fun close() { order += "global"; editor.close(); throw globalFailure }
                },
                object : SessionSettingsViewModel by session {
                    override fun close() { order += "session"; session.close(); throw sessionFailure }
                },
                object : NewSessionSettingsViewModel by defaults {
                    override fun close() { order += "defaults"; defaults.close(); throw defaultsFailure }
                },
            )
            try {
                assertSame(defaultsFailure, assertFailsWith<IllegalArgumentException> { vm.close() })
                assertEquals(listOf("global", "session", "defaults"), order)
                assertTrue(editor.applicationPreferences.state.value.closed)
                assertFalse(defaults.state.value.active)
                vm.close()
                vm.selectPage(SettingsPage.OpenAi)
                assertEquals(listOf("global", "session", "defaults"), order)
                assertEquals(SettingsPage.General, vm.selectedPage.value)
                assertNotNull(services.global.getSettings())
            } finally { editor.close(); session.close(); defaults.close(); global.close(); global.join() }
        }
    }
    test("settings root observes independent authorities and closes only its children") {
        withRpcFrontend {
            val global = RpcGlobalSettings.open(services.global, openCliFrontendSettings(root), this, 120)
            val view = views.open(create("target").sessionIndex)
            val editor = RpcGlobalEditor(global, services.global, this)
            val session = createSessionSettingsViewModel(RpcSessionSettingsSource(view, this), models, this)
            val vm = createSettingsViewModel(SettingsPage.General, editor, session, RpcNewSessionSettings(global, this))
            try {
                vm.global.applicationPreferences.setNewLineKey(NewLineKey.Enter)
                vm.global.sessionTitleSettings.setEnabled(true)
                editor.applicationPreferences.state.first { it.newLineKey == NewLineKey.Enter }
                editor.sessionTitleSettings.state.first { it.enabled }
                val available = assertIs<SessionSettingsState.Available>(session.state.value)
                session.updateReasoningEffort(available.snapshot.revision, ReasoningEffort.High)
                view.current().settings.first { it.reasoning.effort == ReasoningEffort.High }
                vm.selectPage(SettingsPage.CurrentSession)
                assertEquals(SettingsPage.CurrentSession, vm.selectedPage.value)
                vm.close()
                editor.applicationPreferences.setNewLineKey(NewLineKey.ShiftEnter)
                assertEquals(NewLineKey.Enter, global.frontend.settings.value.newLineKey)
                assertEquals("target", view.current().settings.value.threadName)
                assertNotNull(services.global.getSettings())
            } finally { vm.close(); global.close(); global.join() }
        }
    }
    test("navigation retains component handles and refreshes only on entering OpenAI") {
        withRpcFrontend {
            val refreshes = MutableStateFlow(0)
            val rpc = object : io.github.stream29.kodex.rpc.contract.GlobalRpc by services.global {
                override suspend fun refreshAccountUsage() { refreshes.value++ }
            }
            val global = RpcGlobalSettings.open(rpc, openCliFrontendSettings(root), this, 120)
            val editor = RpcGlobalEditor(global, rpc, this)
            val view = views.open(create("navigation").sessionIndex)
            val session = createSessionSettingsViewModel(RpcSessionSettingsSource(view, this), models, this)
            val vm = createSettingsViewModel(SettingsPage.General, editor, session, RpcNewSessionSettings(global, this))
            try {
                val sources = vm.global.contextSourceSettings
                val authentication = vm.global.authenticationSettings
                val usage = vm.global.accountUsage
                val reset = vm.global.usageReset
                assertEquals(0, refreshes.value) // constructors do not refresh.
                vm.selectPage(SettingsPage.ContextSources)
                sources.add()
                val draft = assertIs<io.github.stream29.kodex.app.contextsourcesettings.ContextSourceSettingsDialog.Adding>(
                    sources.state.value.dialog,
                )
                sources.updateDraft(draft.token, "/unaccepted")
                vm.selectPage(SettingsPage.OpenAi)
                refreshes.first { it == 1 }
                assertIs<io.github.stream29.kodex.app.contextsourcesettings.ContextSourceSettingsDialog.Hidden>(
                    sources.state.value.dialog,
                )
                authentication.requestLogout()
                assertNotNull(authentication.state.value.confirmation)
                reset.show()
                vm.selectPage(SettingsPage.OpenAi) // same page is not re-entry.
                vm.selectPage(SettingsPage.General)
                assertNull(authentication.state.value.confirmation)
                assertEquals(UsageResetState.Hidden, reset.state.value)
                vm.selectPage(SettingsPage.OpenAi)
                refreshes.first { it == 2 }
                assertSame(sources, vm.global.contextSourceSettings)
                assertSame(authentication, vm.global.authenticationSettings)
                assertSame(usage, vm.global.accountUsage)
                assertSame(reset, vm.global.usageReset)
                vm.close()
                assertTrue(sources.state.value.closed)
                assertTrue(authentication.state.value.closed)
                assertTrue(usage.state.value.closed)
                assertTrue(vm.global.sessionTitleSettings.state.value.closed)
                assertTrue(vm.global.applicationPreferences.state.value.closed)
                assertEquals(UsageResetState.Hidden, reset.state.value)
                assertNotNull(services.global.getSettings()) // shared backend remains alive.
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
                val sources = editor.contextSourceSettings
                sources.setBuiltInEnabled(BuiltInContextSource.WorkingDirectory, false)
                sources.add()
                val add = assertIs<io.github.stream29.kodex.app.contextsourcesettings.ContextSourceSettingsDialog.Adding>(
                    sources.state.value.dialog,
                )
                sources.updateDraft(add.token, contextPath)
                sources.save(add.token)
                global.settings.first { !it.contextSources.workingDirectoryEnabled && it.contextSources.customSources.size == 1 }
                val hook = NotificationHook("notify", NotificationHookType.entries.toSet(), "echo test")
                val child = editor.hookSettings
                child.add()
                val adding = assertIs<io.github.stream29.kodex.app.hooksettings.HookSettingsDialog.Editing>(child.state.value.dialog)
                child.updateDraft(adding.token) { io.github.stream29.kodex.app.hooksettings.HookEditorDraft(
                    hook.name, hook.command, hook.types,
                ) }
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
                child.updateDraft(editing.token) { it.copy(command = "echo changed") }
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
                sources.removeCustom(contextPath)
                global.settings.first { it.contextSources.customSources.isEmpty() }
            } finally { editor.close(); global.close(); global.join() }
        }
    }
}
