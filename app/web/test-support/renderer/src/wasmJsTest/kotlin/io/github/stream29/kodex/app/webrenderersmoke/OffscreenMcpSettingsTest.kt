package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.mcpsettings.*
import io.github.stream29.kodex.app.settings.contract.McpServerSettingsState
import io.github.stream29.kodex.app.settings.contract.McpServerSettingsStatus
import io.github.stream29.kodex.app.ui.compose.rememberComposeMcpDialogRenderer
import io.github.stream29.kodex.cli.settings.*
import io.github.stream29.kodex.mcp.contract.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

val offscreenMcpSettingsTest by testSuite {
    test("unavailable provider sign-in disables login without disabling MCP details") { offscreenTest {
        var logins = 0
        var edits = 0
        val scene = OffscreenScene.create {
            McpDetailsContent(
                mcpServer().copy(authentication = McpAuthenticationState.LoginRequired),
                {}, { edits++ }, {}, {}, { logins++ }, {}, {}, {},
                loginAvailable = false,
            ) { _, _, content -> io.github.stream29.kodex.app.ui.UiColumn(content) }
        }
        try {
            scene.settle { scene.hasText("Log in") }
            scene.click("Log in")
            assertEquals(0, logins)
            scene.click("Edit")
            assertEquals(1, edits)
            assertTrue(scene.hasText("Provider sign-in is unavailable in this host. Use the Native client."))
        } finally { scene.close() }
    } }
    for ((auth, action) in listOf(
        McpAuthenticationState.LoginRequired to "Log in",
        McpAuthenticationState.Authorized to "Log out",
        McpAuthenticationState.Authorizing to "Cancel login",
    )) {
        test("shared MCP details $action expose only sanitized fields and bounded runtime actions") { offscreenTest {
            var calls = 0
            val server = mcpServer().copy(
                authentication = auth, status = McpServerSettingsStatus.Failed(McpClientFailureReason.Transport),
                oauth = McpOAuthSummary(null, true, "https://example.invalid/callback", null, null, null, emptyList()),
            )
            val scene = OffscreenScene.create {
                rememberComposeMcpDialogRenderer().Details(server, {}, {}, {}, {}, { calls++ }, { calls++ }, { calls++ }, { calls++ })
            }
            try {
                scene.settle { scene.hasText("Headers: Authorization (values hidden)") }
                assertTrue(scene.hasText("OAuth client secret: Configured"))
                assertTrue(scene.hasText("OAuth client: Dynamic registration pending"))
                assertTrue(scene.hasText("Reconnect"))
                for (other in listOf("Log in", "Log out", "Cancel login").filter { it != action }) assertFalse(scene.hasText(other))
                scene.showMcpText(action)
                scene.click(action)
                assertEquals(1, calls)
                scene.showMcpText("Reconnect")
                scene.click("Reconnect")
                assertEquals(2, calls)
                scene.frame()
            } finally { scene.close() }
        } }
    }
    test("MCP editor transport changes preserve inactive fields and same-frame edits retain Keep markers") { mcpScene { ports, child ->
        child.details("server")
        child.edit((child.state.value.dialog as McpSettingsDialog.Details).token)
        val scene = OffscreenScene.create { McpSettingsDialogHost(child, rememberComposeMcpDialogRenderer()) }
        try {
            scene.settle { scene.hasDescription("Transport: HTTP") }
            scene.editMcpField("Server name", " renamed-server ")
            scene.editMcpField("URL", "https://new.invalid/mcp")
            scene.clickDescription("Transport: HTTP")
            scene.settle { scene.hasDescription("Transport: stdio") }
            scene.clickDescription("Transport: stdio")
            scene.settle { scene.hasText("Command") }
            scene.editMcpField("Command", "mcp-offline")
            scene.editMcpField("Arguments (space separated)", "--flag argument")
            scene.editMcpField("Working directory", "/fixture")
            val draft = (child.state.value.dialog as McpSettingsDialog.Editing).draft
            assertEquals(" renamed-server ", draft.name)
            assertEquals("https://new.invalid/mcp", draft.httpUrl)
            assertEquals("mcp-offline", draft.command)
            scene.scrollMcpDialog(-10_000f)
            scene.clickDescription("Transport: stdio")
            scene.settle { scene.hasDescription("Transport: HTTP") }
            scene.clickDescription("Transport: HTTP")
            scene.settle { scene.hasText("URL") }
            scene.showMcpText("Save")
            scene.click("Save")
            scene.settle { child.state.value.dialog == McpSettingsDialog.Hidden }
            val saved = assertIs<McpServerDraft.StreamableHttp>(ports.saved.single())
            assertEquals("renamed-server", saved.serverName)
            assertEquals("https://new.invalid/mcp", saved.configuration.url)
            assertEquals<Map<String, McpSecretDraft>>(mapOf("Authorization" to McpSecretDraft.Keep), saved.configuration.headers)
            assertEquals("server", ports.servers.value.single().serverName)
            assertEquals(1, ports.editReleases)
        } finally { scene.close() }
        assertFalse(child.state.value.closed)
    } }
    test("MCP import renders loading failure retry and keeps filtered-out decisions owned by VM") { mcpScene { ports, child ->
        ports.readGate = CompletableDeferred()
        ports.readFailure = IllegalStateException("private file detail")
        val scene = OffscreenScene.create {
            McpSettingsPage(child)
            McpSettingsDialogHost(child, rememberComposeMcpDialogRenderer())
        }
        try {
            scene.settle { scene.hasText("Import from Codex") }
            scene.click("Import from Codex")
            scene.settle { scene.hasText("Loading Codex MCP servers…") }
            ports.readGate?.complete(Unit)
            scene.settle { scene.hasText("The Codex MCP settings could not be loaded.") }
            assertFalse(scene.hasText("private file detail"))
            ports.readGate = null
            ports.readFailure = null
            scene.click("Retry")
            scene.settle { scene.hasText("Import selected (2)") }
            scene.showMcpText("– unsupported Unsupported")
            scene.click("– unsupported Unsupported")
            assertTrue(ports.applied.isEmpty())
            scene.showMcpText("Clear")
            scene.click("Clear")
            scene.settle { scene.hasText("Import selected (0)") }
            scene.click("Import selected (0)")
            assertTrue(ports.applied.isEmpty())
            scene.scrollMcpDialog(-10_000f)
            scene.editMcpField("Filter", "nothing-matches")
            scene.settle { scene.hasText("No Codex MCP servers found.") }
            scene.click("Select all")
            scene.settle { scene.hasText("Import selected (2)") }
            val decisions = (child.state.value.dialog as McpSettingsDialog.ImportPreview).decisions
            assertEquals(3, decisions.size)
            assertEquals(McpImportDecision.Skip, decisions["unsupported"])
            ports.admission = McpWriteAdmission.Rejected("Import target changed.")
            scene.click("Import selected (2)")
            scene.settle { scene.hasText("Import target changed.") }
            assertTrue(ports.applied.isEmpty())
            ports.admission = McpWriteAdmission.Accepted
            scene.click("Import selected (2)")
            scene.settle { child.state.value.dialog == McpSettingsDialog.Hidden }
            assertEquals(listOf(mapOf(
                "new" to McpImportDecision.Import, "conflict" to McpImportDecision.Replace, "unsupported" to McpImportDecision.Skip,
            )), ports.applied)
            assertEquals(1, ports.importReleases)
            assertEquals(2, ports.reads)
        } finally { scene.close() }
    } }
    test("MCP effect consumer survives page hiding and a late opening failure cannot cancel a newer login") { mcpScene { ports, child ->
        child.details("server")
        val token = (child.state.value.dialog as McpSettingsDialog.Details).token
        val visible = MutableStateFlow(true)
        val opening = CompletableDeferred<Unit>()
        var calls = 0
        val scene = OffscreenScene.create {
            McpSettingsEffects(child) {
                calls++
                if (calls == 1) { opening.await(); false } else true
            }
            if (visible.collectAsState().value) McpSettingsPage(child)
        }
        try {
            scene.settle { scene.hasText("MCP servers") }
            child.login(token)
            scene.settle { calls == 1 }
            ports.logins.first().completion.complete(Unit)
            scene.settle { ports.logins.first().cancellations == 1 }
            child.login(token)
            scene.settle { ports.logins.size == 2 }
            child.hidePage()
            visible.value = false
            opening.complete(Unit)
            scene.settle { calls == 2 && !scene.hasText("MCP servers") }
            assertEquals(1, ports.logins.first().cancellations)
            assertEquals(0, ports.logins.last().cancellations)
            assertEquals(McpAuthenticationState.LoginRequired, ports.servers.value.single().authentication)
            assertFalse(scene.hasText("https://example.invalid/authorize"))
        } finally { scene.close() }
    } }
}

private fun OffscreenScene.editMcpField(label: String, text: String) {
    val root = nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true }
    fun action(node: SemanticsNode): ((AnnotatedString) -> Boolean)? =
        node.config.getOrNull(SemanticsActions.SetText)?.action ?: node.children.firstNotNullOfOrNull(::action)
    assertTrue(assertNotNull(action(root))(AnnotatedString(text)))
}

private suspend fun OffscreenScene.scrollMcpDialog(delta: Float) {
    val scrollers = nodes().filter { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
        .mapNotNull { it.config.getOrNull(SemanticsActions.ScrollBy)?.action }
    assertTrue(scrollers.isNotEmpty())
    scrollers.first()(0f, delta)
    settle { true }
}

private suspend fun OffscreenScene.showMcpText(label: String) {
    for (delta in listOf(0f, -10_000f, 10_000f)) {
        if (delta != 0f) scrollMcpDialog(delta)
        if (hasText(label) && text(label).boundsInWindow.height > 0 && text(label).boundsInWindow.width > 0) return
    }
    error("MCP dialog control is not visible: $label")
}

private suspend fun mcpScene(block: suspend (McpPorts, McpSettingsViewModel) -> Unit) = offscreenTest {
    val ports = McpPorts()
    val owner = CoroutineScope(currentCoroutineContext() + Job(currentCoroutineContext()[Job]))
    val child = createMcpSettingsViewModel(ports, owner)
    try { block(ports, child) } finally { child.close(); owner.coroutineContext.job.cancelAndJoin() }
}

private fun mcpServer(): McpServerSettingsState = McpServerSettingsState(
    "server", McpTransportKind.StreamableHttp, true, McpAuthenticationState.LoginRequired,
    McpServerSettingsStatus.Connecting, headerNames = listOf("Authorization"), streamableHttpUrl = "https://example.invalid/mcp",
)

internal class McpPorts : McpSettingsDependencies {
    override val servers = MutableStateFlow(listOf(mcpServer()))
    override val operationFailure = MutableStateFlow(false)
    var admission: McpWriteAdmission = McpWriteAdmission.Accepted
    val saved = mutableListOf<McpServerDraft>()
    val applied = mutableListOf<Map<String, McpImportDecision>>()
    val logins = mutableListOf<Login>()
    var editReleases = 0
    var importReleases = 0
    var readGate: CompletableDeferred<Unit>? = null
    var readFailure: Throwable? = null
    var reads = 0
    override fun captureEditor(serverName: String?): McpEditHandle = object : McpEditHandle {
        override val originalName = serverName
        override val initialDraft = if (serverName == null) null else McpServerDraft.StreamableHttp(
            serverName, configuration = McpStreamableHttpDraft("https://example.invalid/mcp", mapOf("Authorization" to McpSecretDraft.Keep)),
        )
        override fun save(draft: McpServerDraft): McpWriteAdmission {
            if (admission == McpWriteAdmission.Accepted) saved += draft
            return admission
        }
        private var released = false
        override fun release() { if (!released) { released = true; editReleases++ } }
    }
    override fun captureServer(serverName: String): McpServerHandle? = null
    override suspend fun readImport(): McpImportHandle {
        reads++
        readGate?.await()
        readFailure?.let { throw it }
        val snapshot = McpImportPreview(reads.toLong(), "", listOf(
            McpImportItem("new", McpTransportKind.StreamableHttp, McpImportItemKind.New, true, true),
            McpImportItem("conflict", McpTransportKind.Stdio, McpImportItemKind.Conflict, true, true),
            McpImportItem("unsupported", null, McpImportItemKind.Unsupported, null, false),
        ))
        return object : McpImportHandle {
            override val preview = snapshot
            override fun filter(filter: String): McpImportPreview = snapshot.copy(filter = filter, items = snapshot.items.filter { it.serverName.contains(filter, true) })
            override fun apply(decisions: Map<String, McpImportDecision>): McpWriteAdmission {
                if (admission == McpWriteAdmission.Accepted) applied += decisions.toMap()
                return admission
            }
            private var released = false
            override fun release() { if (!released) { released = true; importReleases++ } }
        }
    }
    override suspend fun startLogin(serverName: String, interactionScope: CoroutineScope): McpSettingsLogin =
        Login(serverName).also(logins::add)
    override suspend fun reconnect(serverName: String) = Unit
    override suspend fun logout(serverName: String) = Unit
    override fun reportFailure(failure: Throwable) { operationFailure.value = true }
    override fun dismissFailure() { operationFailure.value = false }
    class Login(override val serverName: String) : McpSettingsLogin {
        override val authorizationUrl = "https://example.invalid/authorize"
        val completion = CompletableDeferred<Unit>()
        var cancellations = 0
        override suspend fun awaitCompletion() { completion.await() }
        override fun cancel() { if (cancellations == 0) cancellations++ }
    }
}
