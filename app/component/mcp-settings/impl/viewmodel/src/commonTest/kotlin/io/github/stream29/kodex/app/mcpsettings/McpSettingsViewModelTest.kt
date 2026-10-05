@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.app.mcpsettings

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.settings.contract.McpServerSettingsState
import io.github.stream29.kodex.app.settings.contract.McpServerSettingsStatus
import io.github.stream29.kodex.mcp.contract.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.*

val mcpSettingsViewModelTest by testSuite {
    test("draft updates use current fields once and a failing update leaves validation intact") {
        runTest {
            val vm = createMcpSettingsViewModel(McpPorts(), backgroundScope)
            try {
                vm.add()
                val token = vm.editor().token
                vm.save(token)
                val invalid = vm.state.value
                val failure = IllegalArgumentException("injected draft update failure")
                assertSame(failure, assertFailsWith<IllegalArgumentException> {
                    vm.updateDraft(token) { throw failure }
                })
                assertSame(invalid, vm.state.value)
                var updates = 0
                vm.updateDraft(token) { updates++; it.copy(name = "latest") }
                vm.updateDraft(token) { updates++; it.copy(httpUrl = "https://latest.invalid/mcp") }
                assertEquals(2, updates)
                assertEquals("latest", vm.editor().draft.name)
                assertEquals("https://latest.invalid/mcp", vm.editor().draft.httpUrl)
                assertNull(vm.editor().error)
                vm.close()
                vm.updateDraft(token) { error("Closed owner must not invoke update") }
            } finally { vm.close() }
        }
    }
    test("construction observes sanitized rows without implicit Codex read or commands") {
        runTest {
            val deps = McpPorts()
            val vm = createMcpSettingsViewModel(deps, backgroundScope)
            runCurrent()
            assertEquals(deps.servers.value, vm.state.value.servers)
            assertEquals(0, deps.reads)
            assertTrue(deps.saved.isEmpty())
            deps.servers.value = emptyList()
            runCurrent()
            assertTrue(vm.state.value.servers.isEmpty())
            vm.close()
        }
    }
    test("draft validation remains VM owned and a valid save only admits once") {
        runTest {
            val deps = McpPorts()
            val vm = createMcpSettingsViewModel(deps, backgroundScope)
            vm.add()
            val token = vm.editor().token
            vm.save(token)
            assertEquals("Server name is required.", vm.editor().error)
            vm.updateDraft(token) { it.copy(name = "  added  ") }
            vm.save(token)
            assertEquals("A URL or command is required.", vm.editor().error)
            vm.updateDraft(token) { it.copy(httpUrl = "  https://new.example/mcp  ") }
            assertNull(vm.editor().error)
            vm.save(token)
            vm.save(token)
            val saved = deps.saved.single().second as McpServerDraft.StreamableHttp
            assertEquals("added", saved.serverName)
            assertEquals("https://new.example/mcp", saved.configuration.url)
            assertEquals(1, deps.editors.single().releases)
            assertEquals(McpSettingsDialog.Hidden, vm.state.value.dialog)
            assertEquals("existing", vm.state.value.servers.single().serverName)
            vm.close()
        }
    }
    test("bound editor keeps baseline Keep markers and rename does not rebase") {
        runTest {
            val deps = McpPorts()
            deps.initial = McpServerDraft.StreamableHttp(
                "existing", false, McpStreamableHttpDraft(
                    "https://old.example/mcp",
                    mapOf("Authorization" to McpSecretDraft.Keep),
                    McpOAuthDraft(clientSecret = McpSecretDraft.Keep),
                ),
            )
            val vm = createMcpSettingsViewModel(deps, backgroundScope)
            runCurrent()
            vm.details("existing")
            vm.edit(vm.detailsToken())
            val editor = vm.editor()
            assertEquals("Authorization=<keep>", editor.draft.headers)
            assertEquals("<keep>", editor.draft.oauthClientSecret)
            assertFalse(editor.draft.enabled)
            val captured = deps.editors.single().baseline
            deps.baseline = "newer baseline"
            deps.servers.value = listOf(deps.servers.value.single().copy(streamableHttpUrl = "https://latest/mcp"))
            runCurrent()
            vm.updateDraft(editor.token) { it.copy(name = "renamed") }
            vm.save(editor.token)
            assertEquals(captured, deps.saved.single().first)
            val saved = deps.saved.single().second as McpServerDraft.StreamableHttp
            assertEquals(McpSecretDraft.Keep, saved.configuration.headers["Authorization"])
            assertEquals(McpSecretDraft.Keep, saved.configuration.oauth?.clientSecret)
            assertEquals("renamed", saved.serverName)
            assertEquals("https://old.example/mcp", saved.configuration.url)
            vm.close()
        }
    }
    test("rejected stale or duplicate editor admission retains text and baseline") {
        runTest {
            val deps = McpPorts()
            deps.admission = McpWriteAdmission.Rejected("The selected name is no longer available.")
            val vm = createMcpSettingsViewModel(deps, backgroundScope)
            vm.add()
            val editor = vm.editor()
            vm.updateDraft(editor.token) { it.copy(name = "existing", httpUrl = "https://a/mcp") }
            vm.save(editor.token)
            assertSame(editor.token, vm.editor().token)
            assertEquals("existing", vm.editor().draft.name)
            assertEquals("The selected name is no longer available.", vm.editor().error)
            assertTrue(deps.saved.isEmpty())
            assertTrue(deps.failures.isEmpty())
            assertEquals(0, deps.editors.single().releases)
            vm.close()
        }
    }
    test("details use latest named row and disappearing target invalidates callbacks") {
        runTest {
            val deps = McpPorts()
            val vm = createMcpSettingsViewModel(deps, backgroundScope)
            runCurrent()
            vm.details("existing")
            val token = vm.detailsToken()
            deps.servers.value = listOf(deps.servers.value.single().copy(
                status = McpServerSettingsStatus.Healthy(7),
            ))
            runCurrent()
            assertEquals(McpServerSettingsStatus.Healthy(7),
                (vm.state.value.dialog as McpSettingsDialog.Details).server.status)
            deps.servers.value = emptyList()
            runCurrent()
            vm.edit(token)
            vm.requestDelete(token)
            vm.setEnabled(token, false)
            vm.reconnect(token)
            assertEquals(McpSettingsDialog.Hidden, vm.state.value.dialog)
            assertTrue(deps.editors.isEmpty())
            assertTrue(deps.targets.isEmpty())
            assertTrue(deps.reconnections.isEmpty())
            vm.close()
        }
    }
    test("delete capture is exact confirmation baseline while enable captures click baseline") {
        runTest {
            val deps = McpPorts()
            val vm = createMcpSettingsViewModel(deps, backgroundScope)
            vm.details("existing")
            vm.setEnabled(vm.detailsToken(), false)
            assertEquals("opening baseline" to false, deps.enabled.single())
            assertEquals(1, deps.targets.single().releases)
            deps.baseline = "delete baseline"
            vm.requestDelete(vm.detailsToken())
            val deletion = vm.state.value.dialog as McpSettingsDialog.Deleting
            deps.baseline = "later baseline"
            vm.confirmDelete(deletion.token)
            vm.confirmDelete(deletion.token)
            assertEquals(listOf("delete baseline"), deps.deleted)
            assertEquals(1, deps.targets.last().releases)
            vm.close()
        }
    }
    test("reconnect logout invoke exact names and do not synthesize runtime health") {
        runTest {
            val deps = McpPorts()
            val vm = createMcpSettingsViewModel(deps, backgroundScope)
            vm.details("existing")
            val token = vm.detailsToken()
            vm.reconnect(token)
            vm.logout(token)
            runCurrent()
            assertEquals(listOf("existing"), deps.reconnections)
            assertEquals(listOf("existing"), deps.logouts)
            assertEquals(McpServerSettingsStatus.Connecting, vm.state.value.servers.single().status)
            vm.close()
        }
    }
    test("replacement hide and close release handles and reject every late editor callback") {
        runTest {
            val deps = McpPorts()
            val vm = createMcpSettingsViewModel(deps, backgroundScope)
            vm.add()
            val old = vm.editor()
            vm.add()
            val next = vm.editor()
            vm.updateDraft(old.token) { error("A stale callback must not execute its update") }
            vm.save(old.token)
            vm.dismiss(old.token)
            assertSame(next.token, vm.editor().token)
            assertEquals("", vm.editor().draft.name)
            vm.hidePage()
            vm.save(next.token)
            assertEquals(1, deps.editors.first().releases)
            assertEquals(1, deps.editors.last().releases)
            vm.close()
            vm.add()
            vm.importCodex()
            vm.details("existing")
            assertTrue(vm.state.value.closed)
            assertEquals(McpSettingsDialog.Hidden, vm.state.value.dialog)
            assertTrue(deps.saved.isEmpty())
            assertEquals(0, deps.reads)
        }
    }
    test("explicit import defaults filtering and unsupported toggles stay in VM") {
        runTest {
            val deps = McpPorts()
            val vm = createMcpSettingsViewModel(deps, backgroundScope)
            vm.importCodex()
            assertIs<McpSettingsDialog.ImportLoading>(vm.state.value.dialog)
            runCurrent()
            val token = vm.preview().token
            assertEquals(mapOf("new" to McpImportDecision.Import, "conflict" to McpImportDecision.Replace,
                "unsupported" to McpImportDecision.Skip), vm.preview().decisions)
            vm.toggleImport(token, "unsupported")
            assertEquals(McpImportDecision.Skip, vm.preview().decisions["unsupported"])
            vm.toggleImport(token, "new")
            vm.filterImport(token, "conf")
            assertEquals(listOf("conflict"), vm.preview().preview.items.map { it.serverName })
            assertEquals(McpImportDecision.Skip, vm.preview().decisions["new"])
            assertEquals(1, deps.reads)
            vm.clearImports(token)
            vm.applyImport(token)
            assertTrue(deps.imported.isEmpty())
            vm.selectAllImports(token)
            assertEquals(McpImportDecision.Import, vm.preview().decisions["new"])
            vm.applyImport(token)
            vm.applyImport(token)
            assertEquals(1, deps.imported.size)
            assertEquals(1, deps.imports.single().releases)
            assertEquals(McpSettingsDialog.Hidden, vm.state.value.dialog)
            vm.close()
            // Fake queue drains later; release and close did not erase its copied decisions.
            assertEquals(McpImportDecision.Replace, deps.imported.single().second["conflict"])
        }
    }
    test("import rejection keeps preview and decisions for retry") {
        runTest {
            val deps = McpPorts()
            deps.admission = McpWriteAdmission.Rejected("The import target changed. Open a new preview.")
            val vm = createMcpSettingsViewModel(deps, backgroundScope)
            vm.importCodex()
            runCurrent()
            val token = vm.preview().token
            vm.applyImport(token)
            assertSame(token, vm.preview().token)
            assertNotNull(vm.preview().error)
            assertEquals(0, deps.imports.single().releases)
            assertTrue(deps.failures.isEmpty())
            vm.hidePage()
            assertEquals(1, deps.imports.single().releases)
            vm.close()
        }
    }
    test("import read error is generic with explicit retry and application failure") {
        runTest {
            val deps = McpPorts()
            deps.readFailure = IllegalStateException("raw private Codex configuration")
            val vm = createMcpSettingsViewModel(deps, backgroundScope)
            vm.importCodex()
            runCurrent()
            val failed = vm.state.value.dialog as McpSettingsDialog.ImportFailed
            assertEquals(1, deps.failures.size)
            assertTrue(vm.state.value.operationFailure)
            deps.readFailure = null
            vm.retryImport(failed.token)
            runCurrent()
            assertIs<McpSettingsDialog.ImportPreview>(vm.state.value.dialog)
            assertEquals(2, deps.reads)
            vm.close()
        }
    }
    test("cancelled read does not become failure or eternal loading") {
        runTest {
            val deps = McpPorts()
            deps.readFailure = CancellationException("cancelled")
            val vm = createMcpSettingsViewModel(deps, backgroundScope)
            vm.importCodex()
            runCurrent()
            assertEquals(McpSettingsDialog.Hidden, vm.state.value.dialog)
            assertTrue(deps.failures.isEmpty())
            vm.close()
        }
    }
    test("late noncooperative import completion is released and cannot reopen replacement") {
        runTest {
            val deps = McpPorts()
            val finish = CompletableDeferred<Unit>()
            deps.delayedRead = finish
            val vm = createMcpSettingsViewModel(deps, backgroundScope)
            vm.importCodex()
            runCurrent()
            vm.add()
            val next = vm.editor().token
            finish.complete(Unit)
            runCurrent()
            assertSame(next, vm.editor().token)
            assertEquals(1, deps.imports.single().releases)
            assertTrue(deps.imported.isEmpty())
            vm.close()
        }
    }
    test("OAuth effect failure cancels exact old attempt not newer same name") {
        runTest {
            val deps = McpPorts()
            val vm = createMcpSettingsViewModel(deps, backgroundScope)
            val effects = mutableListOf<McpSettingsEffect.OpenAuthorizationUrl>()
            backgroundScope.launch { vm.effects.collect { effects += it as McpSettingsEffect.OpenAuthorizationUrl } }
            vm.details("existing")
            vm.login(vm.detailsToken())
            vm.login(vm.detailsToken()) // duplicate ignored
            runCurrent()
            assertEquals(1, deps.logins.size)
            assertEquals(1, effects.size)
            deps.logins.first().finished.complete(Unit)
            runCurrent()
            vm.login(vm.detailsToken())
            runCurrent()
            assertEquals(2, deps.logins.size)
            effects.first().cancel()
            runCurrent()
            assertEquals(1, deps.logins.first().cancellations)
            assertEquals(0, deps.logins.last().cancellations)
            effects.last().cancel()
            runCurrent()
            assertEquals(1, deps.logins.last().cancellations)
            assertTrue(deps.failures.isEmpty())
            vm.close()
        }
    }
    test("page hide keeps OAuth and Settings close cleans only owned exact attempt") {
        runTest {
            val deps = McpPorts()
            val vm = createMcpSettingsViewModel(deps, backgroundScope)
            backgroundScope.launch { vm.effects.collect {} }
            vm.details("existing")
            vm.login(vm.detailsToken())
            runCurrent()
            vm.hidePage()
            runCurrent()
            assertEquals(0, deps.logins.single().cancellations)
            vm.close()
            runCurrent()
            assertEquals(1, deps.logins.single().cancellations)
            assertTrue(deps.failures.isEmpty())
        }
    }
    test("late OAuth preparation after close cleans exact handle without emitting a URL") {
        runTest {
            val deps = McpPorts()
            val prepared = CompletableDeferred<Unit>()
            deps.loginGate = prepared
            val vm = createMcpSettingsViewModel(deps, backgroundScope)
            var effects = 0
            backgroundScope.launch { vm.effects.collect { effects++ } }
            vm.details("existing")
            vm.login(vm.detailsToken())
            runCurrent()
            vm.close()
            prepared.complete(Unit)
            runCurrent()
            assertEquals(0, effects)
            assertEquals(1, deps.logins.single().cancellations)
            assertTrue(deps.failures.isEmpty())
        }
    }
    test("OAuth preparation failure reports generically while cancellation does not") {
        runTest {
            val deps = McpPorts()
            val vm = createMcpSettingsViewModel(deps, backgroundScope)
            vm.details("existing")
            deps.loginFailure = IllegalStateException("private authorization details")
            vm.login(vm.detailsToken())
            runCurrent()
            assertEquals(1, deps.failures.size)
            deps.loginFailure = CancellationException("cancel")
            vm.login(vm.detailsToken())
            runCurrent()
            assertEquals(1, deps.failures.size)
            vm.close()
        }
    }
    test("dependency closing during admission cannot reopen editor on rejection") {
        runTest {
            val deps = McpPorts()
            val vm = createMcpSettingsViewModel(deps, backgroundScope)
            vm.add()
            val token = vm.editor().token
            vm.updateDraft(token) { McpEditorDraft(name = "valid", httpUrl = "https://valid/mcp") }
            deps.onWrite = { vm.close() }
            deps.admission = McpWriteAdmission.Rejected("No longer accepting.")
            vm.save(token)
            assertTrue(vm.state.value.closed)
            assertEquals(McpSettingsDialog.Hidden, vm.state.value.dialog)
            assertEquals(1, deps.editors.single().releases)
        }
    }
    test("unknown admission failure reports once generically cancellation is unchanged") {
        runTest {
            val deps = McpPorts()
            val vm = createMcpSettingsViewModel(deps, backgroundScope)
            vm.add()
            val token = vm.editor().token
            vm.updateDraft(token) { McpEditorDraft(name = "valid", httpUrl = "https://valid/mcp") }
            deps.writeFailure = IllegalStateException("private configuration")
            vm.save(token)
            runCurrent()
            assertEquals(1, deps.failures.size)
            assertEquals("The MCP configuration could not be queued.", vm.editor().error)
            val cancellation = CancellationException("cancel")
            deps.writeFailure = cancellation
            assertSame(cancellation, assertFailsWith<CancellationException> { vm.save(token) })
            assertEquals(1, deps.failures.size)
            vm.close()
            val reopened = createMcpSettingsViewModel(deps, backgroundScope)
            assertTrue(reopened.state.value.operationFailure)
            reopened.dismissFailure()
            runCurrent()
            assertFalse(reopened.state.value.operationFailure)
            reopened.close()
        }
    }
    test("owner termination cleans observation and captured unaccepted resources") {
        runTest {
            val deps = McpPorts()
            val owner = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
            val vm = createMcpSettingsViewModel(deps, owner)
            vm.add()
            runCurrent()
            owner.cancel()
            runCurrent()
            assertTrue(vm.state.value.closed)
            assertEquals(1, deps.editors.single().releases)
            vm.close()
        }
    }
}

private fun McpSettingsViewModel.editor(): McpSettingsDialog.Editing = state.value.dialog as McpSettingsDialog.Editing
private fun McpSettingsViewModel.preview(): McpSettingsDialog.ImportPreview = state.value.dialog as McpSettingsDialog.ImportPreview
private fun McpSettingsViewModel.detailsToken(): McpDialogToken = (state.value.dialog as McpSettingsDialog.Details).token

private class McpPorts : McpSettingsDependencies {
    override val servers = MutableStateFlow(listOf(McpServerSettingsState(
        "existing", McpTransportKind.StreamableHttp, true, McpAuthenticationState.LoginRequired,
        McpServerSettingsStatus.Connecting, streamableHttpUrl = "https://existing/mcp",
    )))
    override val operationFailure = MutableStateFlow(false)
    var baseline = "opening baseline"
    var initial: McpServerDraft = McpServerDraft.StreamableHttp(
        "existing", configuration = McpStreamableHttpDraft("https://existing/mcp"),
    )
    var admission: McpWriteAdmission = McpWriteAdmission.Accepted
    var writeFailure: Throwable? = null
    var readFailure: Throwable? = null
    var delayedRead: CompletableDeferred<Unit>? = null
    var loginGate: CompletableDeferred<Unit>? = null
    var loginFailure: Throwable? = null
    var onWrite: (() -> Unit)? = null
    var reads = 0
    val editors = mutableListOf<Editor>()
    val targets = mutableListOf<Target>()
    val imports = mutableListOf<Import>()
    val logins = mutableListOf<Login>()
    val saved = mutableListOf<Pair<String, McpServerDraft>>()
    val deleted = mutableListOf<String>()
    val enabled = mutableListOf<Pair<String, Boolean>>()
    val imported = mutableListOf<Pair<Long, Map<String, McpImportDecision>>>()
    val failures = mutableListOf<Throwable>()
    val reconnections = mutableListOf<String>()
    val logouts = mutableListOf<String>()
    override fun captureEditor(serverName: String?): McpEditHandle? {
        if (serverName != null && servers.value.none { it.serverName == serverName }) return null
        return Editor(serverName, baseline, if (serverName == null) null else initial).also(editors::add)
    }
    inner class Editor(
        override val originalName: String?, val baseline: String,
        override val initialDraft: McpServerDraft?,
    ) : McpEditHandle {
        var releases = 0
        override fun save(draft: McpServerDraft): McpWriteAdmission {
            onWrite?.invoke()
            writeFailure?.let { throw it }
            if (admission == McpWriteAdmission.Accepted) saved += baseline to draft
            return admission
        }
        override fun release() { releases++ }
    }
    override fun captureServer(serverName: String): McpServerHandle? =
        if (servers.value.none { it.serverName == serverName }) null
        else Target(serverName, baseline).also(targets::add)
    inner class Target(override val serverName: String, val baseline: String) : McpServerHandle {
        var releases = 0
        override fun delete(): McpWriteAdmission {
            writeFailure?.let { throw it }
            if (admission == McpWriteAdmission.Accepted) deleted += baseline
            return admission
        }
        override fun setEnabled(enabled: Boolean): McpWriteAdmission {
            writeFailure?.let { throw it }
            if (admission == McpWriteAdmission.Accepted) this@McpPorts.enabled += baseline to enabled
            return admission
        }
        override fun release() { releases++ }
    }
    override suspend fun readImport(): McpImportHandle {
        reads++
        readFailure?.let { throw it }
        val handle = Import(reads.toLong()).also(imports::add)
        delayedRead?.let { withContext(NonCancellable) { it.await() } }
        return handle
    }
    inner class Import(id: Long) : McpImportHandle {
        var releases = 0
        override val preview = McpImportPreview(id, "", listOf(
            McpImportItem("new", McpTransportKind.StreamableHttp, McpImportItemKind.New, true, true),
            McpImportItem("conflict", McpTransportKind.Stdio, McpImportItemKind.Conflict, true, true),
            McpImportItem("unsupported", null, McpImportItemKind.Unsupported, null, false),
        ))
        override fun filter(filter: String): McpImportPreview =
            preview.copy(filter = filter, items = preview.items.filter { it.serverName.contains(filter, true) })
        override fun apply(decisions: Map<String, McpImportDecision>): McpWriteAdmission {
            check(releases == 0)
            writeFailure?.let { throw it }
            if (admission == McpWriteAdmission.Accepted) imported += preview.id to decisions.toMap()
            return admission
        }
        override fun release() { releases++ }
    }
    override suspend fun startLogin(serverName: String, interactionScope: CoroutineScope): McpSettingsLogin {
        loginFailure?.let { throw it }
        val login = Login(serverName).also(logins::add)
        loginGate?.let { withContext(NonCancellable) { it.await() } }
        return login
    }
    class Login(override val serverName: String) : McpSettingsLogin {
        override val authorizationUrl = "https://login.example/authorize"
        val finished = CompletableDeferred<Unit>()
        var cancellations = 0
        override suspend fun awaitCompletion() { finished.await() }
        override fun cancel() { cancellations++ }
    }
    override suspend fun reconnect(serverName: String) { reconnections += serverName }
    override suspend fun logout(serverName: String) { logouts += serverName }
    override fun reportFailure(failure: Throwable) { failures += failure; operationFailure.value = true }
    override fun dismissFailure() { operationFailure.value = false }
}

val mcpEditorParserTest by testSuite {
    test("stdio quoted escaped empty arguments round trip and cwd remains text") {
        val original = McpServerDraft.Stdio("stdio", false, McpStdioDraft(
            command = "server", args = listOf("", "two words", "quote\"", "back\\slash", "single'quote"),
            environment = mapOf("TOKEN" to McpSecretDraft.Keep), workingDirectory = Path("../cwd"),
        ))
        assertEquals(original, original.editorFields().validatedDraft())
        val saved = McpEditorDraft(
            name = " name ", transport = McpTransportKind.Stdio, command = " cmd ",
            arguments = "one 'two words' \"\" escaped\\ space",
            environment = "TOKEN=<keep>;OTHER=a=b", workingDirectory = " ",
        ).validatedDraft() as McpServerDraft.Stdio
        assertEquals(listOf("one", "two words", "", "escaped space"), saved.configuration.args)
        assertEquals(Path("."), saved.configuration.workingDirectory)
        assertEquals(McpSecretDraft.Keep, saved.configuration.environment["TOKEN"])
        assertEquals(McpSecretDraft.Replace("a=b"), saved.configuration.environment["OTHER"])
    }
    test("secret Keep Replace Remove and OAuth options preserve baseline parser semantics") {
        val fields = McpEditorDraft(
            name = "http", httpUrl = "https://http/mcp", headers = "A=<keep>;B= new value ",
            oauthEnabled = true, oauthClientId = " client ", oauthClientSecret = "<keep>",
            oauthAuthorizationEndpoint = " ", oauthTokenEndpoint = " https://token ",
            oauthResource = " resource ", oauthScopes = " one, ,two,one ",
        )
        val draft = fields.validatedDraft() as McpServerDraft.StreamableHttp
        assertEquals(mapOf("A" to McpSecretDraft.Keep, "B" to McpSecretDraft.Replace(" new value")),
            draft.configuration.headers)
        assertEquals("client", draft.configuration.oauth?.clientId)
        assertEquals(McpSecretDraft.Keep, draft.configuration.oauth?.clientSecret)
        assertEquals(listOf("one", "two", "one"), draft.configuration.oauth?.scopes)
        val removed = fields.copy(headers = "", oauthClientSecret = "").validatedDraft() as McpServerDraft.StreamableHttp
        assertTrue(removed.configuration.headers.isEmpty())
        assertNull(removed.configuration.oauth?.clientSecret)
        val disabled = fields.copy(oauthEnabled = false).validatedDraft() as McpServerDraft.StreamableHttp
        assertNull(disabled.configuration.oauth)
    }
    for ((fields, message) in listOf(
        McpEditorDraft(name = "a", httpUrl = "url", headers = "bad") to "Each secret entry must use KEY=value.",
        McpEditorDraft(name = "a", httpUrl = "url", headers = "A=x;A=y") to "Secret entry names must be unique.",
        McpEditorDraft(name = "a", transport = McpTransportKind.Stdio, command = "cmd", arguments = "'bad") to "An argument quote is not closed.",
        McpEditorDraft(name = "a", transport = McpTransportKind.Stdio, command = "cmd", arguments = "bad\\") to "An argument cannot end with an escape character.",
        McpEditorDraft(name = "a", httpUrl = "url", oauthEnabled = true, oauthRedirect = " ") to "OAuth redirect URI is required.",
    )) {
        test("validation displays $message") {
            assertEquals(message, assertFailsWith<IllegalArgumentException> { fields.validatedDraft() }.message)
        }
    }
}
