package io.github.stream29.kodex.cli.settings

import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.mcpsettings.*
import io.github.stream29.kodex.app.settings.contract.McpServerSettingsState
import io.github.stream29.kodex.app.settings.contract.McpServerSettingsStatus
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.mcp.contract.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

val mcpSettingsComponentTest by testSuite {
    test("full renderer empty list and failure use actual component state") {
        val deps = McpRenderPorts()
        deps.servers.value = emptyList()
        deps.operationFailure.value = true
        withMcpVm(deps) { vm ->
            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    TuiPopupHost(Modifier.width(100).height(36)) { McpSettingsComponent(vm) }
                }
                assertTrue("MCP servers [Add] [Import from Codex]" in snapshot, snapshot)
                assertTrue("None configured" in snapshot, snapshot)
                assertTrue("A settings operation failed." in snapshot, snapshot)
                assertEquals(0, deps.reads)
            }
        }
    }
    for ((status, label) in listOf(
        McpServerSettingsStatus.Disabled to "Disabled",
        McpServerSettingsStatus.AuthenticationBlocked(McpAuthenticationState.LoginRequired) to "Authentication: Login required",
        McpServerSettingsStatus.Connecting to "Connecting",
        McpServerSettingsStatus.Healthy(1) to "Healthy (1 tool)",
        McpServerSettingsStatus.Failed(McpClientFailureReason.Transport) to "Failed: Transport",
        McpServerSettingsStatus.Closed to "Closed",
    )) {
        test("full renderer runtime row $label is a read only projection") {
            val deps = McpRenderPorts()
            deps.servers.value = listOf(deps.servers.value.single().copy(status = status))
            withMcpVm(deps) { vm ->
                runMosaicTest {
                    val snapshot = setContentAndSnapshot {
                        TuiPopupHost(Modifier.width(100).height(36)) { McpSettingsComponent(vm) }
                    }
                    assertTrue(label in snapshot, snapshot)
                    assertFalse("Headers:" in snapshot, snapshot)
                    assertTrue(deps.saved.isEmpty())
                }
            }
        }
    }
    for ((authentication, action) in listOf(
        McpAuthenticationState.NotConfigured to null,
        McpAuthenticationState.LoginRequired to "[Log in]",
        McpAuthenticationState.ReauthorizationRequired to "[Log in]",
        McpAuthenticationState.Failed("safe failure") to "[Log in]",
        McpAuthenticationState.Authorizing to "[Cancel login]",
        McpAuthenticationState.Authorized to "[Log out]",
        McpAuthenticationState.Refreshing to "[Log out]",
    )) {
        test("full renderer details authentication $authentication offers bounded actions") {
            val deps = McpRenderPorts()
            deps.servers.value = listOf(deps.servers.value.single().copy(
                authentication = authentication,
                status = McpServerSettingsStatus.Failed(McpClientFailureReason.ConnectionLost),
            ))
            withMcpVm(deps) { vm ->
                vm.details("server")
                runMosaicTest {
                    val snapshot = setContentAndSnapshot {
                        TuiPopupHost(Modifier.width(100).height(36)) { McpSettingsComponent(vm) }
                    }
                    assertTrue("Headers: Authorization (values hidden)" in snapshot, snapshot)
                    assertTrue("[Reconnect]" in snapshot, snapshot)
                    if (action == null) {
                        assertFalse("[Log in]" in snapshot, snapshot)
                        assertFalse("[Log out]" in snapshot, snapshot)
                        assertFalse("[Cancel login]" in snapshot, snapshot)
                    } else assertTrue(action in snapshot, snapshot)
                }
            }
        }
    }
    test("full editor consumes VM validation and transport selection updates its business draft") {
        val deps = McpRenderPorts()
        withMcpVm(deps) { vm ->
            vm.add()
            val token = vm.editorToken()
            vm.save(token)
            runMosaicTest {
                val initial = setContentAndSnapshot {
                    TuiPopupHost(Modifier.width(100).height(36)) { McpSettingsComponent(vm) }
                }
                assertTrue("Server name is required." in initial, initial)
                sendKeyEvent(KeyboardEvent(codepoint = 'N'.code))
                awaitMcpSnapshot("Transport [HTTP]")
                assertEquals("N", (vm.state.value.dialog as McpSettingsDialog.Editing).draft.name)
                assertNull((vm.state.value.dialog as McpSettingsDialog.Editing).error)
                sendKeyEvent(KeyboardEvent(codepoint = 9))
                sendKeyEvent(KeyboardEvent(codepoint = 13))
                awaitMcpSnapshot("[stdio]")
                sendKeyEvent(KeyboardEvent(codepoint = KeyboardEvent.Down))
                sendKeyEvent(KeyboardEvent(codepoint = 13))
                val stdio = awaitMcpSnapshot("Transport [stdio]")
                assertTrue("Arguments (space separated)" in stdio, stdio)
                assertTrue("Working directory" in stdio, stdio)
                assertEquals(McpTransportKind.Stdio,
                    (vm.state.value.dialog as McpSettingsDialog.Editing).draft.transport)
                sendKeyEvent(KeyboardEvent(codepoint = 27))
                awaitMcpSnapshot("MCP servers [Add]")
                assertTrue(deps.saved.isEmpty())
            }
        }
    }
    test("edit displays sanitized Keep draft OAuth fields and VM validation") {
        val deps = McpRenderPorts()
        deps.initial = McpServerDraft.StreamableHttp("server", configuration = McpStreamableHttpDraft(
            "https://server/mcp", mapOf("Authorization" to McpSecretDraft.Keep),
            McpOAuthDraft(clientId = "client", clientSecret = McpSecretDraft.Keep),
        ))
        withMcpVm(deps) { vm ->
            vm.details("server")
            vm.edit((vm.state.value.dialog as McpSettingsDialog.Details).token)
            val editor = vm.state.value.dialog as McpSettingsDialog.Editing
            vm.updateDraft(editor.token, editor.draft.copy(oauthRedirect = ""))
            vm.save(editor.token)
            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    TuiPopupHost(Modifier.width(100).height(45)) { McpSettingsComponent(vm) }
                }
                assertTrue("Edit MCP server" in snapshot, snapshot)
                assertTrue("Authorization=<keep>" in snapshot, snapshot)
                assertTrue("[x] OAuth" in snapshot, snapshot)
                assertTrue("OAuth redirect URI is required." in snapshot, snapshot)
                assertTrue("Scopes (comma separated)" in snapshot, snapshot)
            }
        }
    }
    test("delete confirmation cancel default does not admit delete") {
        val deps = McpRenderPorts()
        withMcpVm(deps) { vm ->
            vm.details("server")
            vm.requestDelete((vm.state.value.dialog as McpSettingsDialog.Details).token)
            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    TuiPopupHost(Modifier.width(100).height(36)) { McpSettingsComponent(vm) }
                }
                assertTrue("Delete 'server'?" in snapshot, snapshot)
                sendKeyEvent(KeyboardEvent(codepoint = 13))
                awaitMcpSnapshot("MCP servers [Add]")
                assertEquals(0, deps.deletions)
            }
        }
    }
    test("full import loading failed retry and decisions are component owned") {
        val deps = McpRenderPorts()
        val gate = CompletableDeferred<Unit>()
        deps.readGate = gate
        deps.readFailure = IllegalStateException("private file details")
        withMcpVm(deps) { vm ->
            vm.importCodex()
            runMosaicTest {
                val loading = setContentAndSnapshot {
                    TuiPopupHost(Modifier.width(100).height(36)) { McpSettingsComponent(vm) }
                }
                assertTrue("Loading Codex MCP servers" in loading, loading)
                gate.complete(Unit)
                val failed = awaitMcpSnapshot("The Codex MCP settings could not be loaded.")
                assertTrue("[Retry]" in failed, failed)
                assertFalse("private file details" in failed, failed)
                deps.readFailure = null
                vm.retryImport((vm.state.value.dialog as McpSettingsDialog.ImportFailed).token)
                val preview = awaitMcpSnapshot("[Import selected (2)]")
                assertTrue("[✓ new New]" in preview, preview)
                assertTrue("[✓ conflict Replace existing]" in preview, preview)
                assertTrue("[– unsupported Unsupported]" in preview, preview)
                val token = (vm.state.value.dialog as McpSettingsDialog.ImportPreview).token
                vm.toggleImport(token, "new")
                awaitMcpSnapshot("[Import selected (1)]")
                assertEquals(McpImportDecision.Skip,
                    (vm.state.value.dialog as McpSettingsDialog.ImportPreview).decisions["new"])
                vm.applyImport(token)
                awaitMcpSnapshot("MCP servers [Add]")
                assertEquals(McpImportDecision.Replace, deps.applied.single()["conflict"])
                assertEquals(McpImportDecision.Skip, deps.applied.single()["new"])
            }
        }
    }
    test("full import empty preview and admission rejection render without local authority") {
        val deps = McpRenderPorts()
        deps.items = emptyList()
        withMcpVm(deps) { vm ->
            vm.importCodex()
            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    TuiPopupHost(Modifier.width(100).height(36)) { McpSettingsComponent(vm) }
                }
                assertTrue("No Codex MCP servers found." in snapshot, snapshot)
                assertTrue("[Import selected (0)]" in snapshot, snapshot)
                deps.items = supportedItems()
                deps.admission = McpWriteAdmission.Rejected("The import target changed.")
                vm.importCodex()
                awaitMcpSnapshot("[Import selected (2)]")
                vm.applyImport((vm.state.value.dialog as McpSettingsDialog.ImportPreview).token)
                awaitMcpSnapshot("The import target changed.")
                assertTrue(deps.applied.isEmpty())
            }
        }
    }
    test("late browser opening failure uses exact effect callback even after new login") {
        val deps = McpRenderPorts()
        val opening = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        var calls = 0
        withMcpVm(deps) { vm ->
            vm.details("server")
            runMosaicTest {
                setContentAndSnapshot {
                    TuiPopupHost(Modifier.width(100).height(36)) { McpSettingsComponent(vm) }
                    McpSettingsEffects(vm) {
                        calls++
                        if (calls == 1) {
                            started.complete(Unit)
                            opening.await()
                            false
                        } else true
                    }
                }
                val token = (vm.state.value.dialog as McpSettingsDialog.Details).token
                vm.login(token)
                withTimeout(1_000) { started.await() }
                deps.logins.first().completion.complete(Unit)
                yield()
                vm.login(token)
                assertEquals(2, deps.logins.size)
                vm.hidePage() // effect consumer must stay installed while MCP is hidden
                opening.complete(Unit)
                yield()
                awaitMcpSnapshot("MCP servers [Add]")
                assertEquals(1, deps.logins.first().cancellations)
                assertEquals(0, deps.logins.last().cancellations)
            }
        }
    }
}

private fun McpSettingsViewModel.editorToken(): McpDialogToken =
    (state.value.dialog as McpSettingsDialog.Editing).token

private suspend fun withMcpVm(deps: McpRenderPorts, action: suspend (McpSettingsViewModel) -> Unit) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val vm = createMcpSettingsViewModel(deps, scope)
    try { action(vm) } finally { vm.close(); scope.cancel() }
}

private suspend fun TestMosaic<String>.awaitMcpSnapshot(expected: String): String {
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

private fun supportedItems(): List<McpImportItem> = listOf(
    McpImportItem("new", McpTransportKind.StreamableHttp, McpImportItemKind.New, true, true),
    McpImportItem("conflict", McpTransportKind.Stdio, McpImportItemKind.Conflict, true, true),
    McpImportItem("unsupported", null, McpImportItemKind.Unsupported, null, false),
)

private class McpRenderPorts : McpSettingsDependencies {
    override val servers = MutableStateFlow(listOf(McpServerSettingsState(
        "server", McpTransportKind.StreamableHttp, true, McpAuthenticationState.LoginRequired,
        McpServerSettingsStatus.Connecting, headerNames = listOf("Authorization"),
        streamableHttpUrl = "https://server/mcp",
    )))
    override val operationFailure = MutableStateFlow(false)
    var initial: McpServerDraft = McpServerDraft.StreamableHttp("server",
        configuration = McpStreamableHttpDraft("https://server/mcp"))
    var admission: McpWriteAdmission = McpWriteAdmission.Accepted
    var items = supportedItems()
    var readGate: CompletableDeferred<Unit>? = null
    var readFailure: Throwable? = null
    var reads = 0
    var deletions = 0
    val saved = mutableListOf<McpServerDraft>()
    val applied = mutableListOf<Map<String, McpImportDecision>>()
    val logins = mutableListOf<Login>()
    override fun captureEditor(serverName: String?): McpEditHandle = object : McpEditHandle {
        override val originalName = serverName
        override val initialDraft = if (serverName == null) null else initial
        override fun save(draft: McpServerDraft): McpWriteAdmission {
            if (admission == McpWriteAdmission.Accepted) saved += draft
            return admission
        }
        override fun release() = Unit
    }
    override fun captureServer(serverName: String): McpServerHandle = object : McpServerHandle {
        override val serverName = serverName
        override fun delete(): McpWriteAdmission { deletions++; return admission }
        override fun setEnabled(enabled: Boolean): McpWriteAdmission = admission
        override fun release() = Unit
    }
    override suspend fun readImport(): McpImportHandle {
        reads++
        readGate?.await()
        readFailure?.let { throw it }
        val snapshot = McpImportPreview(reads.toLong(), "", items)
        return object : McpImportHandle {
            override val preview = snapshot
            override fun filter(filter: String): McpImportPreview = snapshot.copy(
                filter = filter, items = snapshot.items.filter { it.serverName.contains(filter, true) },
            )
            override fun apply(decisions: Map<String, McpImportDecision>): McpWriteAdmission {
                if (admission == McpWriteAdmission.Accepted) applied += decisions.toMap()
                return admission
            }
            override fun release() = Unit
        }
    }
    override suspend fun startLogin(serverName: String, interactionScope: CoroutineScope): McpSettingsLogin =
        Login(serverName).also(logins::add)
    class Login(override val serverName: String) : McpSettingsLogin {
        override val authorizationUrl = "https://login/authorize"
        val completion = CompletableDeferred<Unit>()
        var cancellations = 0
        override suspend fun awaitCompletion() { completion.await() }
        override fun cancel() { cancellations++ }
    }
    override suspend fun reconnect(serverName: String) = Unit
    override suspend fun logout(serverName: String) = Unit
    override fun reportFailure(failure: Throwable) { operationFailure.value = true }
    override fun dismissFailure() { operationFailure.value = false }
}
