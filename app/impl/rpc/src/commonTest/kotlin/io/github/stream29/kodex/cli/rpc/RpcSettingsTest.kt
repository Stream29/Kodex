@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.cli.rpc

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.settings.contract.*
import io.github.stream29.kodex.app.settings.createSessionSettingsViewModel
import io.github.stream29.kodex.cli.settings.*
import io.github.stream29.kodex.mcp.contract.*
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.openai.accountusage.*
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetCredit
import io.github.stream29.kodex.rpc.client.asSuspendMutableStateFlow
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import io.github.stream29.kodex.rpc.models.*
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.io.files.Path
import kotlinx.rpc.RpcClient
import kotlinx.rpc.RpcCall
import kotlin.test.*
import kotlin.time.Instant

private fun queueHook(page: RpcGlobalEditor, hook: NotificationHook) {
    val child = page.hookSettings
    child.add()
    val editing = assertIs<io.github.stream29.kodex.app.hooksettings.HookSettingsDialog.Editing>(
        child.state.value.dialog,
    )
    child.updateDraft(editing.token) { io.github.stream29.kodex.app.hooksettings.HookEditorDraft(
        name = hook.name, command = hook.command, types = hook.types,
    ) }
    child.save(editing.token)
}

val rpcSettingsTest by testSuite(compartment = { TestCompartment.RealTime }) {
    for (closing in listOf(true, false)) {
        test("global observation preserves ordinary failure but cancellation wins while closing=$closing") {
            frontend { supervisorScope {
                val entered = CompletableDeferred<Unit>()
                val proceed = CompletableDeferred<Unit>()
                val ready = CompletableDeferred<RpcGlobalSettings>()
                val error = IllegalStateException("controlled transport failure")
                val rpc = object : GlobalRpc by services.global {
                    override fun getModelsFlow(): Flow<List<ModelInfo>> = flow {
                        entered.complete(Unit)
                        try { proceed.await() } finally { throw error }
                    }
                }
                val task = async {
                    coroutineScope {
                        val global = RpcGlobalSettings.open(rpc, openCliFrontendSettings(home), this, 80)
                        try {
                            ready.complete(global)
                            awaitCancellation()
                        } finally { global.close(); global.join() }
                    }
                }
                try {
                    val global = ready.await()
                    entered.await()
                    if (closing) task.cancel() else proceed.complete(Unit)
                    val failure = runCatching { task.await() }.exceptionOrNull()
                    assertFailsWith<CancellationException> { global.ensureActive() }
                    if (closing) assertIs<CancellationException>(failure, "Close must not become a late transport failure.")
                    else {
                        assertIs<IllegalStateException>(failure)
                        // Coroutine stacktrace recovery can copy this exception for await().
                        assertSame(error, failure.cause ?: failure,
                            "A live subscription failure must remain observable.")
                    }
                } finally {
                    proceed.complete(Unit)
                    task.cancelAndJoin()
                }
            } }
        }
    }
    test("real Hook adapter keeps stale and same-name queued writes unchanged after release") {
        frontend {
            val store = openCliFrontendSettings(home)
            val global = RpcGlobalSettings.open(services.global, store, this, 80)
            val queue = io.github.stream29.kodex.app.settings.SettingsUpdateQueue(
                this, defaultReportError = global::reportOperationFailure,
            )
            val entered = CompletableDeferred<Unit>()
            val proceed = CompletableDeferred<Unit>()
            val drained = CompletableDeferred<Unit>()
            var accepting = true
            val dependencies = RpcHookSettingsDependencies(
                global, global.frontend.settings.projectState { it.hooks },
            ) { action ->
                if (!accepting) false else {
                    queue.submit { action(); global.dismissOperationFailure() }
                    true
                }
            }
            val original = NotificationHook(
                "same", setOf(NotificationHookType.StopAssistantMessage), "echo original",
            )
            val replacement = original.copy(command = "echo replacement")
            val added = original.copy(name = "admitted", command = "echo admitted")
            try {
                global.updateHooks(listOf(original))
                queue.submit { entered.complete(Unit); proceed.await() }
                entered.await()
                val stale = requireNotNull(dependencies.captureEditor(original.name))
                val conflictingAdd = requireNotNull(dependencies.captureEditor(null))
                val admittedAdd = requireNotNull(dependencies.captureEditor(null))
                assertEquals(io.github.stream29.kodex.app.hooksettings.HookWriteAdmission.Accepted,
                    stale.save(original.copy(command = "echo stale")))
                assertEquals(io.github.stream29.kodex.app.hooksettings.HookWriteAdmission.Accepted,
                    conflictingAdd.save(original.copy(command = "echo conflict")))
                assertEquals(io.github.stream29.kodex.app.hooksettings.HookWriteAdmission.Accepted,
                    admittedAdd.save(added))
                assertEquals(io.github.stream29.kodex.app.hooksettings.HookWriteAdmission.Accepted,
                    dependencies.delete(original))
                stale.release()
                conflictingAdd.release()
                admittedAdd.release()
                assertIs<io.github.stream29.kodex.app.hooksettings.HookWriteAdmission.Rejected>(
                    admittedAdd.save(added.copy(command = "echo too late")),
                )
                // Mutate the real frontend store before the admitted closures execute.
                global.updateHooks(listOf(replacement))
                accepting = false
                queue.close { drained.complete(Unit) }
                assertEquals(listOf(replacement), store.settings.value.hooks)
                global.reportOperationFailure(IllegalStateException("safe test failure"))
                proceed.complete(Unit)
                drained.await()
                assertEquals(listOf(replacement, added), store.settings.value.hooks)
                assertEquals(listOf(replacement, added), openCliFrontendSettings(home).settings.value.hooks)
                assertFalse(global.operationFailure.value)
            } finally {
                proceed.complete(Unit)
                queue.close { drained.complete(Unit) }
                drained.await()
                global.close()
                global.join()
            }
        }
    }
    test("field retries merge unrelated changes and do not publish a successful reply") {
        runTest {
            val source = MutableStateFlow("old" to 1)
            var calls = 0
            var written: Pair<String, Int>? = null
            val state = source.asSuspendMutableStateFlow { _, update ->
                calls++
                if (calls == 1) { source.value = "old" to 2; false }
                else { written = update; true }
            }
            assertTrue(state.editField("old", { it.first }, { "new" to it.second }))
            assertEquals(50, currentTime)
            assertEquals("new" to 2, written)
            assertEquals("old" to 2, state.value)
        }
    }
    test("target conflicts stop retries without resetting the baseline") {
        runTest {
            val source = MutableStateFlow("old")
            var calls = 0
            val state = source.asSuspendMutableStateFlow { _, _ -> calls++; source.value = "other"; false }
            assertFalse(state.editField("old", { it }, { "new" }))
            assertEquals(1, calls)
            assertEquals("other", state.value)
        }
    }
    test("no subscription progress still retries at 50ms and cancellation ends the loop") {
        runTest {
            var calls = 0
            val state = MutableStateFlow(1).asSuspendMutableStateFlow { _, _ -> calls++; false }
            val work = launch { state.editField(1, { it }, { 2 }) }
            runCurrent()
            advanceTimeBy(149)
            assertEquals(3, calls)
            work.cancelAndJoin()
            advanceTimeBy(1000)
            assertEquals(3, calls)
        }
    }
    test("unknown write failure is not compared or retried") {
        runTest {
            var calls = 0
            val state = MutableStateFlow(1).asSuspendMutableStateFlow { _, _ -> calls++; error("lost reply") }
            assertFailsWith<IllegalStateException> { state.editField(1, { it }, { 2 }) }
            assertEquals(1, calls)
        }
    }
    test("global observations and frontend-only settings have separate persistence") {
        frontend {
            val store = openCliFrontendSettings(home)
            val global = RpcGlobalSettings.open(services.global, store, this, 120)
            try {
                assertEquals(30 to 30, global.sidebarWidths.value)
                global.resizeSidebars(17, 29)
                global.updateHooks(listOf(NotificationHook("notify", setOf(NotificationHookType.StopAssistantMessage), "echo local")))
                assertNotNull(SystemCoroutineFileSystem.metadataOrNull(Path(home, "settings.frontend.cli.yml")))
                assertNull(SystemCoroutineFileSystem.metadataOrNull(Path(home, "settings.backend.yml")))
                val initial = global.settings.value.sessionTitle
                assertTrue(global.edit(initial, { it.sessionTitle }, { it.copy(sessionTitle = initial.copy(enabled = true)) }))
                global.settings.first { it.sessionTitle.enabled }
                assertEquals(store.settings.value, openCliFrontendSettings(home).settings.value)
                assertEquals(17 to 29, global.sidebarWidths.value)
                assertTrue(global.models.value.isNotEmpty())
            } finally { global.close(); global.join() }
            assertFailsWith<CancellationException> {
                global.edit(global.settings.value.authSource, { it.authSource }, { it })
            }
        }
    }
    test("failed initial Get is not replaced with frontend defaults") {
        frontend {
            val store = openCliFrontendSettings(home)
            val rpc = object : GlobalRpc by services.global {
                override suspend fun getSettings(): BackendSettings = error("unavailable")
            }
            assertFailsWith<IllegalStateException> { RpcGlobalSettings.open(rpc, store, this, 120) }
            assertNull(SystemCoroutineFileSystem.metadataOrNull(store.settingsPath))
        }
    }
    test("Session editor uses real CAS and becomes unavailable after its binding closes") {
        frontend {
            val view = views.open(services.global.createSession(KodexAgentSettings(OpenAiModelId("test-model"))))
            val source = RpcSessionSettingsSource(view, this)
            try {
                val expected = assertIs<SessionSettingsDataState.Available>(source.state.value).snapshot
                assertTrue(source.tryRenameSession(expected.revision, "renamed"))
                source.state.first { it is SessionSettingsDataState.Available && it.snapshot.sessionName == "renamed" }
                assertFalse(source.tryRenameSession(expected.revision, "stale"))
                views.release(view)
                source.state.first { it == SessionSettingsDataState.Unavailable }
                assertFalse(source.tryRenameSession(expected.revision, "closed"))
            } finally { source.close() }
        }
    }
    test("closing the settings popup cancels a paced pending CAS edit") {
        val calls = MutableStateFlow(0)
        frontend(decorate = { delegate ->
            object : RpcClient by delegate {
                @Suppress("UNCHECKED_CAST")
                override suspend fun <T> call(call: RpcCall): T {
                    if (call.callableName == "compareAndSet") {
                        calls.value += 1
                        return false as T
                    }
                    return delegate.call(call)
                }
            }
        }) {
            val view = views.open(services.global.createSession(KodexAgentSettings(OpenAiModelId("test-model"))))
            val source = RpcSessionSettingsSource(view, this)
            val vm = createSessionSettingsViewModel(source, MutableStateFlow(emptyList()), this)
            val initial = assertIs<SessionSettingsState.Available>(vm.state.value).snapshot
            vm.renameSession(initial.revision, "queued")
            calls.first { it > 0 }
            vm.close()
            val stopped = calls.value
            delay(150)
            assertEquals(stopped, calls.value)
            assertEquals(SessionSettingsDataState.Unavailable, source.state.value)
        }
    }
    test("defaults editor observes CAS changes and rejects delayed local revisions") {
        frontend {
            val global = RpcGlobalSettings.open(services.global, openCliFrontendSettings(home), this, 80)
            val vm = RpcNewSessionSettings(global, this)
            try {
                val initial = vm.state.value
                vm.updateModel(initial.revision, OpenAiModelId("another-model"))
                vm.state.first { it.settings.model == OpenAiModelId("another-model") }
                vm.updateModel(initial.revision, OpenAiModelId("stale"))
                assertEquals(OpenAiModelId("another-model"), services.global.getSettings().newSession.model)
                vm.close()
                vm.updateModel(vm.state.value.revision, OpenAiModelId("closed"))
                assertEquals(OpenAiModelId("another-model"), services.global.getSettings().newSession.model)
            } finally { vm.close(); global.close(); global.join() }
        }
    }
    test("global page uses separate local preferences and preserves unrelated concurrent fields") {
        frontend {
            val global = RpcGlobalSettings.open(services.global, openCliFrontendSettings(home), this, 120)
            val page = RpcGlobalEditor(global, services.global, this)
            try {
                page.sessionTitleSettings.setEnabled(true)
                page.sessionTitleSettings.setModel(OpenAiModelId("title-model"))
                page.applicationPreferences.setNewLineKey(NewLineKey.Enter)
                page.applicationPreferences.setLeftWidth(19)
                page.sessionTitleSettings.state.first {
                    it.enabled && it.configuredModel == OpenAiModelId("title-model")
                }
                page.applicationPreferences.state.first { it.newLineKey == NewLineKey.Enter && it.leftWidth == 19 }
                val hook = NotificationHook("local", setOf(NotificationHookType.StopUnhandledError), "echo error")
                queueHook(page, hook)
                global.frontend.settings.first { it.hooks == listOf(hook) }
                val loaded = openCliFrontendSettings(home)
                assertEquals(NewLineKey.Enter, loaded.settings.value.newLineKey)
                assertEquals(listOf(hook), loaded.settings.value.hooks)
                assertEquals(OpenAiModelId("title-model"), services.global.getSettings().sessionTitle.model)
                page.close()
                page.sessionTitleSettings.setEnabled(false)
                assertTrue(services.global.getSettings().sessionTitle.enabled)
            } finally { page.close(); global.close(); global.join() }
        }
    }
    test("an accepted global settings write survives popup close") {
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        var calls = 0
        frontend(decorate = { delegate ->
            object : RpcClient by delegate {
                override suspend fun <T> call(call: RpcCall): T {
                    if (call.callableName == "compareAndSetSettings") {
                        calls++
                        entered.complete(Unit)
                        proceed.await()
                    }
                    return delegate.call(call)
                }
            }
        }) {
            val global = RpcGlobalSettings.open(services.global, openCliFrontendSettings(home), this, 80)
            val page = RpcGlobalEditor(global, services.global, this)
            try {
                page.sessionTitleSettings.setEnabled(true)
                entered.await()
                page.close()
                proceed.complete(Unit)
                global.settings.first { it.sessionTitle.enabled }
                assertEquals(1, calls)
                assertTrue(services.global.getSettings().sessionTitle.enabled)
            } finally { proceed.complete(Unit); page.close(); global.close(); global.join() }
        }
    }
    test("component width writes preserve nonnegative runtime values without creating a preferences file") {
        frontend {
            val store = openCliFrontendSettings(home)
            val global = RpcGlobalSettings.open(services.global, store, this, 120)
            val page = RpcGlobalEditor(global, services.global, this)
            try {
                page.applicationPreferences.setLeftWidth(0)
                assertEquals(0 to 30, global.sidebarWidths.value)
                global.resizeSidebars(0, 2)
                page.applicationPreferences.setLeftWidth(1)
                assertEquals(1 to 2, global.sidebarWidths.value)
                page.applicationPreferences.state.first { it.leftWidth == MinimumSidebarWidthColumns }
                assertNull(SystemCoroutineFileSystem.metadataOrNull(store.settingsPath))
                page.close()
                page.applicationPreferences.setLeftWidth(-1) // a closed child is a no-op.
                assertEquals(1 to 2, global.sidebarWidths.value)
            } finally { page.close(); global.close(); global.join() }
        }
    }
    test("accepted new-session defaults survive popup close") {
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        frontend(decorate = { delegate ->
            object : RpcClient by delegate {
                override suspend fun <T> call(call: RpcCall): T {
                    if (call.callableName == "compareAndSetSettings") {
                        entered.complete(Unit)
                        proceed.await()
                    }
                    return delegate.call(call)
                }
            }
        }) {
            val global = RpcGlobalSettings.open(services.global, openCliFrontendSettings(home), this, 80)
            val page = RpcNewSessionSettings(global, this)
            try {
                page.updateModel(page.state.value.revision, OpenAiModelId("after-close"))
                entered.await()
                page.close()
                proceed.complete(Unit)
                global.settings.first { it.newSession.model == OpenAiModelId("after-close") }
                assertEquals(OpenAiModelId("after-close"), services.global.getSettings().newSession.model)
            } finally { proceed.complete(Unit); page.close(); global.close(); global.join() }
        }
    }
    test("accepted MCP settings edit keeps its source alive until the write drains") {
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        frontend(decorate = { delegate ->
            object : RpcClient by delegate {
                override suspend fun <T> call(call: RpcCall): T {
                    if (call.callableName == "compareAndSetSettings") {
                        entered.complete(Unit)
                        proceed.await()
                    }
                    return delegate.call(call)
                }
            }
        }) {
            val global = RpcGlobalSettings.open(services.global, openCliFrontendSettings(home), this, 80)
            val page = RpcGlobalEditor(global, services.global, this)
            try {
                page.mcpSettings.add()
                val editing = assertIs<io.github.stream29.kodex.app.mcpsettings.McpSettingsDialog.Editing>(
                    page.mcpSettings.state.value.dialog,
                )
                page.mcpSettings.updateDraft(editing.token) { it.copy(
                    name = "after-close", enabled = false, httpUrl = "https://example.invalid/mcp",
                ) }
                page.mcpSettings.save(editing.token)
                entered.await()
                page.close()
                proceed.complete(Unit)
                global.settings.first { "after-close" in it.mcpServers }
                assertNotNull(services.global.getSettings().mcpServers["after-close"])
            } finally { proceed.complete(Unit); page.close(); global.close(); global.join() }
        }
    }
    test("accepted MCP import retains candidates across hiding and closing its component") {
        frontend {
            val entered = CompletableDeferred<Unit>()
            val proceed = CompletableDeferred<Unit>()
            val candidate = McpServerConfiguration.StreamableHttp("https://example.invalid/import", enabled = false)
            val rpc = object : GlobalRpc by services.global {
                override suspend fun getCodexMcpSettings(): List<McpCodexImportCandidate> =
                    listOf(McpCodexImportCandidate.Supported("imported", candidate))
                override suspend fun compareAndSetSettings(expect: BackendSettings, update: BackendSettings): Boolean {
                    entered.complete(Unit)
                    proceed.await()
                    return services.global.compareAndSetSettings(expect, update)
                }
            }
            val global = RpcGlobalSettings.open(rpc, openCliFrontendSettings(home), this, 80)
            val page = RpcGlobalEditor(global, rpc, this)
            try {
                val child = page.mcpSettings
                child.importCodex()
                val ready = child.state.first {
                    it.dialog is io.github.stream29.kodex.app.mcpsettings.McpSettingsDialog.ImportPreview
                }
                val preview = assertIs<io.github.stream29.kodex.app.mcpsettings.McpSettingsDialog.ImportPreview>(ready.dialog)
                child.applyImport(preview.token)
                entered.await()
                child.hidePage()
                page.close()
                proceed.complete(Unit)
                global.settings.first { it.mcpServers["imported"] == candidate }
                assertEquals(candidate, services.global.getSettings().mcpServers["imported"])
                assertFalse(global.operationFailure.value)
            } finally { proceed.complete(Unit); page.close(); global.close(); global.join() }
        }
    }
    test("MCP and Hook children preserve shared admission order after popup close") {
        frontend {
            val entered = CompletableDeferred<Unit>()
            val proceed = CompletableDeferred<Unit>()
            val rpc = object : GlobalRpc by services.global {
                override suspend fun compareAndSetSettings(expect: BackendSettings, update: BackendSettings): Boolean {
                    entered.complete(Unit)
                    proceed.await()
                    return services.global.compareAndSetSettings(expect, update)
                }
            }
            val store = openCliFrontendSettings(home)
            val global = RpcGlobalSettings.open(rpc, store, this, 80)
            val page = RpcGlobalEditor(global, rpc, this)
            val hook = NotificationHook("queued-after-mcp", setOf(NotificationHookType.StopAssistantMessage), "echo ordered")
            try {
                val child = page.mcpSettings
                child.add()
                val editor = assertIs<io.github.stream29.kodex.app.mcpsettings.McpSettingsDialog.Editing>(
                    child.state.value.dialog,
                )
                child.updateDraft(editor.token) { it.copy(
                    name = "ordered", enabled = false, httpUrl = "https://example.invalid/ordered",
                ) }
                child.save(editor.token)
                entered.await()
                queueHook(page, hook)
                page.close()
                // A per-component queue would write Hook while the earlier MCP CAS is blocked.
                assertTrue(store.settings.value.hooks.isEmpty())
                proceed.complete(Unit)
                store.settings.first { it.hooks == listOf(hook) }
                global.settings.first { "ordered" in it.mcpServers }
                assertFalse(global.operationFailure.value)
            } finally { proceed.complete(Unit); page.close(); global.close(); global.join() }
        }
    }
    test("accepted frontend Hook edit drains after popup close") {
        frontend {
            val store = openCliFrontendSettings(home)
            val global = RpcGlobalSettings.open(services.global, store, this, 80)
            val page = RpcGlobalEditor(global, services.global, this)
            val hook = NotificationHook("after-close", setOf(NotificationHookType.StopAssistantMessage), "echo kept")
            try {
                queueHook(page, hook)
                page.close()
                store.settings.first { it.hooks == listOf(hook) }
                assertEquals(listOf(hook), openCliFrontendSettings(home).settings.value.hooks)
            } finally { page.close(); global.close(); global.join() }
        }
    }
    test("a failed global write remains visible after popup close and clears on success") {
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        var reject = true
        frontend(decorate = { delegate ->
            object : RpcClient by delegate {
                override suspend fun <T> call(call: RpcCall): T {
                    if (call.callableName == "compareAndSetSettings" && reject) {
                        reject = false
                        entered.complete(Unit)
                        proceed.await()
                        error("private-value-must-not-be-rendered")
                    }
                    return delegate.call(call)
                }
            }
        }) {
            val global = RpcGlobalSettings.open(services.global, openCliFrontendSettings(home), this, 80)
            val page = RpcGlobalEditor(global, services.global, this)
            try {
                page.sessionTitleSettings.setEnabled(true)
                entered.await()
                page.close()
                proceed.complete(Unit)
                global.operationFailure.first { it }
                assertFalse(services.global.getSettings().sessionTitle.enabled)
                val reopened = RpcGlobalEditor(global, services.global, this)
                try {
                    assertTrue(reopened.operationFailure.value)
        reopened.sessionTitleSettings.setEnabled(true)
                    global.settings.first { it.sessionTitle.enabled }
                    global.operationFailure.first { !it }
                    reopened.dismissOperationFailure()
                    assertFalse(global.operationFailure.value)
                } finally { reopened.close() }
            } finally { proceed.complete(Unit); page.close(); global.close(); global.join() }
        }
    }
    test("new-session defaults write failures use the shared visible failure state") {
        frontend(decorate = { delegate ->
            object : RpcClient by delegate {
                override suspend fun <T> call(call: RpcCall): T {
                    if (call.callableName == "compareAndSetSettings") error("defaults write rejected")
                    return delegate.call(call)
                }
            }
        }) {
            val global = RpcGlobalSettings.open(services.global, openCliFrontendSettings(home), this, 80)
            val page = RpcNewSessionSettings(global, this)
            try {
                val originalModel = services.global.getSettings().newSession.model
                page.updateModel(page.state.value.revision, OpenAiModelId("failed-default"))
                global.operationFailure.first { it }
                assertEquals(originalModel, services.global.getSettings().newSession.model)
                global.dismissOperationFailure()
                assertFalse(global.operationFailure.value)
            } finally { page.close(); global.close(); global.join() }
        }
    }
    test("settings snapshot revisions ignore backend-only fields while CAS preserves them") {
        frontend {
            val view = views.open(services.global.createSession(KodexAgentSettings(OpenAiModelId("test-model"))))
            val source = RpcSessionSettingsSource(view, this)
            try {
                val before = assertIs<SessionSettingsDataState.Available>(source.state.value).snapshot
                val old = view.current().settings.value
                assertTrue(view.current().settings.compareAndSet(old, old.copy(instructions = "external")))
                view.current().settings.first { it.instructions == "external" }
                assertTrue(source.tryRenameSession(before.revision, "edited"))
                view.current().settings.first { it.threadName == "edited" }
                assertEquals("external", view.current().settings.value.instructions)
            } finally { source.close() }
        }
    }
    test("draft edits remain local and explicit names survive materialization") {
        frontend {
            val draft = RpcSessionDraft(KodexAgentSettings(OpenAiModelId("test-model")), views)
            val source = RpcDraftSettingsSource(draft, this)
            try {
                val expected = assertIs<SessionSettingsDataState.Available>(source.state.value).snapshot
                assertTrue(source.tryRenameSession(expected.revision, "explicit"))
                assertFalse(source.tryRenameSession(expected.revision, "stale"))
                assertTrue(services.global.getSessionCatalog(true).isEmpty())
                val view = draft.materialize()
                view.current().settings.first { it.threadName == "explicit" }
                source.state.first { it == SessionSettingsDataState.Unavailable }
            } finally { source.close(); draft.close() }
        }
    }
    test("MCP edit and import use values only, with sanitized observations") {
        frontend {
            val existing = McpServerConfiguration.StreamableHttp("https://example.invalid/mcp", enabled = false)
            val before = services.global.getSettings()
            services.global.compareAndSetSettings(before, before.copy(mcpServers = mapOf("remote" to existing)))
            var reconnects = 0
            val rpc = object : GlobalRpc by services.global {
                override suspend fun getCodexMcpSettings(): List<McpCodexImportCandidate> =
                    listOf(McpCodexImportCandidate.Supported("remote", existing))
                override suspend fun reconnectMcpServer(serverName: String) { reconnects++ }
            }
            val global = RpcGlobalSettings.open(rpc, openCliFrontendSettings(home), this, 80)
            val mcp = RpcMcpSettings(global, rpc, this)
            try {
                var write: (suspend () -> Unit)? = null
                val imported = mcp.readImport { write = it; true }
                assertEquals(McpImportItemKind.Conflict, imported.preview.items.single().kind)
                assertSame(io.github.stream29.kodex.app.mcpsettings.McpWriteAdmission.Accepted,
                    imported.apply(mapOf("remote" to McpImportDecision.Replace)))
                imported.release()
                requireNotNull(write).invoke()
                assertEquals(0, reconnects)
                assertEquals(existing, services.global.getSettings().mcpServers["remote"])
                mcp.servers.first { it.singleOrNull()?.status == McpServerSettingsStatus.Disabled }
                val captured = mcp.capture()
                assertTrue(mcp.save(captured, "remote", McpServerDraft.StreamableHttp(
                    "renamed", enabled = false, McpStreamableHttpDraft("https://example.invalid/new"),
                )))
                global.settings.first { "renamed" in it.mcpServers }
                assertFalse("remote" in services.global.getSettings().mcpServers)
            } finally { mcp.close(); global.close(); global.join() }
        }
    }
    test("reset with no detailed credits cannot choose or consume") {
        frontend {
            var calls = 0
            val rpc = object : GlobalRpc by services.global {
                override suspend fun consumeUsageReset(creditId: String): CodexRateLimitResetOutcome {
                    calls++; return CodexRateLimitResetOutcome.Reset
                }
            }
            val reset = resetForRpc(rpc, MutableStateFlow(usage(null)), this)
            try {
                reset.show()
                assertEquals(UsageResetState.PreparationFailed, reset.state.value)
                reset.select("anything")
                assertEquals(0, calls)
            } finally { reset.close() }
        }
    }
    test("reset requires exact second confirmation and preserves the returned outcome") {
        frontend {
            val called = mutableListOf<String>()
            val rpc = object : GlobalRpc by services.global {
                override suspend fun consumeUsageReset(creditId: String): CodexRateLimitResetOutcome {
                    called += creditId; return CodexRateLimitResetOutcome.AlreadyRedeemed
                }
                override suspend fun refreshAccountUsage(): Unit = error("refresh failed")
            }
            val reset = resetForRpc(rpc, MutableStateFlow(usage(listOf(credit))), this)
            try {
                reset.show()
                reset.select("credit")
                val confirmation = assertIs<UsageResetState.Confirming>(reset.state.value)
                assertNull(confirmation.option.expiresAt)
                assertTrue(called.isEmpty())
                reset.confirm(confirmation.copy())
                assertTrue(called.isEmpty())
                reset.confirm(confirmation)
                val result = assertIs<UsageResetState.Completed>(reset.state.first { it is UsageResetState.Completed })
                assertEquals(CodexRateLimitResetOutcome.AlreadyRedeemed, result.outcome)
                reset.confirm(confirmation)
                assertEquals(listOf("credit"), called)
            } finally { reset.close() }
        }
    }
    test("unknown reset outcome refreshes once without repeating consumption") {
        frontend {
            var calls = 0
            val refreshed = CompletableDeferred<Unit>()
            val rpc = object : GlobalRpc by services.global {
                override suspend fun consumeUsageReset(creditId: String): CodexRateLimitResetOutcome {
                    calls++; error("reply lost")
                }
                override suspend fun refreshAccountUsage() { refreshed.complete(Unit) }
            }
            val reset = resetForRpc(rpc, MutableStateFlow(usage(listOf(credit))), this)
            try {
                reset.show(); reset.select("credit")
                reset.confirm(assertIs(reset.state.value))
                refreshed.await()
                assertIs<UsageResetState.ConsumeFailed>(reset.state.value)
                assertEquals(1, calls)
            } finally { reset.close() }
        }
    }
}

private val credit = CodexRateLimitResetCredit("credit", null, null)
private fun resetForRpc(
    rpc: GlobalRpc,
    usage: kotlinx.coroutines.flow.StateFlow<SettingsAccountUsageState>,
    scope: CoroutineScope,
): io.github.stream29.kodex.app.usagereset.contract.UsageResetViewModel =
    io.github.stream29.kodex.app.usagereset.createUsageResetViewModel(
        object : io.github.stream29.kodex.app.usagereset.contract.UsageResetDependencies {
            override val usage = usage
            override suspend fun consume(creditId: String): CodexRateLimitResetOutcome = rpc.consumeUsageReset(creditId)
            override suspend fun refreshUsage() { rpc.refreshAccountUsage() }
        }, scope,
    )

private fun usage(credits: List<CodexRateLimitResetCredit>?): SettingsAccountUsageState =
    SettingsAccountUsageState.Available(CodexAccountUsageSnapshot(
        rateLimits = emptyList(), resetCredits = CodexRateLimitResetCredits(1, credits),
        fetchedAt = Instant.parse("2026-09-27T00:00:00Z"),
    ))
