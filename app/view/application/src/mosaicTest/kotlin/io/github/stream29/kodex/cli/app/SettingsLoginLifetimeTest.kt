package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.terminal.Terminal
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Text
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.application.contract.ApplicationPopupState
import io.github.stream29.kodex.app.application.contract.ApplicationViewModel
import io.github.stream29.kodex.app.mcpsettings.*
import io.github.stream29.kodex.app.migration.prepareKodexHome
import io.github.stream29.kodex.app.settings.contract.*
import io.github.stream29.kodex.app.session.contract.SessionViewModel
import io.github.stream29.kodex.app.sessiontabbar.contract.SessionTabIdentity
import io.github.stream29.kodex.app.test.deleteTestDirectory
import io.github.stream29.kodex.app.test.testAnswer
import io.github.stream29.kodex.mcp.contract.*
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.*

val settingsLoginLifetimeTest by testSuite {
    test("root tab identity churn releases departed keys and stale exact close cannot target replacement") {
        withSettingsApplication { app ->
            val root = app.viewModel
            val survivor = requireNotNull(root.navigation.value.selected)
            val identities = mutableMapOf<SessionViewModel, SessionTabIdentity>()
            val survivorIdentity = SessionTabIdentity("survivor")
            identities[survivor] = survivorIdentity
            repeat(40) { index ->
                val departed = root.createNewSessionTab()
                val departedIdentity = SessionTabIdentity("departed-$index")
                identities[departed] = departedIdentity
                // Retain the original exact-target callback, not the later slot or label.
                val staleClose: suspend () -> Boolean = { root.closeTab(departed) }
                assertTrue(root.closeTab(departed))
                pruneSessionTabIdentities(identities, root.navigation.value.tabs)
                assertEquals(setOf(survivor), identities.keys)
                assertSame(survivorIdentity, identities[survivor])
                val replacement = root.createNewSessionTab()
                val before = root.navigation.value
                assertFalse(staleClose())
                assertSame(before, root.navigation.value)
                assertSame(replacement, root.navigation.value.selected)
                assertTrue(root.closeTab(replacement))
            }
        }
    }
    for (phase in listOf("prepare", "opener")) {
        test("real root retains exact Settings MCP consumer during gated $phase and Login return") {
            withSettingsApplication { app ->
                runSettingsRootTest {
                    state.size.value = Terminal.Size(120, 40)
                    val ports = RootMcpPorts()
                    if (phase == "prepare") ports.prepareGate = CompletableDeferred()
                    val opening = CompletableDeferred<Unit>()
                    val opened = CompletableDeferred<Unit>()
                    val calls = mutableListOf<String>()
                    withBoundSettings(app, ports) { root, mcp ->
                        setContentAndSnapshot {
                            SessionTreeCliScreen(root, app.newLineKey, app.sidebarSettings) { url ->
                                calls += url
                                opened.complete(Unit)
                                if (phase == "opener") opening.await()
                                true
                            }
                        }
                        mcp.details("server")
                        clickText(awaitRootText("[Log in]"), "[Log in]")
                        withTimeout(2_000) { ports.preparing.await() }
                        if (phase == "opener") withTimeout(2_000) { opened.await() }
                        root.owner.viewModel.selectPage(SettingsPage.OpenAi)
                        clickText(awaitRootText("[Sign in]"), "[Sign in]")
                        awaitRootText("Sign in to OpenAI")
                        assertSame(root.owner, assertIs<ApplicationPopupState.Login>(root.popup.value).returnTo)
                        assertFalse(mcp.state.value.closed)
                        assertTrue(ports.logins.all { it.cancellations == 0 })

                        ports.prepareGate?.complete(Unit)
                        withTimeout(2_000) { opened.await() }
                        assertEquals(1, calls.size) // no collector handoff or replay
                        assertEquals(0, ports.logins.single().cancellations)
                        opening.complete(Unit)
                        sendKeyEvent(KeyboardEvent(codepoint = 27))
                        awaitRootText("[Sign in]")
                        assertSame(root.owner, root.popup.value)
                        assertSame(root.original, app.viewModel.popup.value)
                        assertEquals(0, ports.logins.single().cancellations)
                        assertEquals(1, calls.size)
                        ports.logins.single().completion.complete(Unit)
                        withTimeout(2_000) { ports.logins.single().cancelled.await() }
                    }
                }
            }
        }
    }

    test("real root late opener failure cancels only captured attempt after Login return") {
        withSettingsApplication { app ->
            runSettingsRootTest {
                state.size.value = Terminal.Size(120, 40)
                val ports = RootMcpPorts()
                val opening = CompletableDeferred<Unit>()
                val opened = CompletableDeferred<Unit>()
                val secondOpened = CompletableDeferred<Unit>()
                var calls = 0
                withBoundSettings(app, ports) { root, mcp ->
                    setContentAndSnapshot {
                        SessionTreeCliScreen(root, app.newLineKey, app.sidebarSettings) {
                            calls++
                            if (calls == 1) {
                                opened.complete(Unit)
                                opening.await()
                                false
                            } else {
                                secondOpened.complete(Unit)
                                true
                            }
                        }
                    }
                    mcp.details("server")
                    clickText(awaitRootText("[Log in]"), "[Log in]")
                    withTimeout(2_000) { opened.await() }
                    root.owner.viewModel.selectPage(SettingsPage.OpenAi)
                    clickText(awaitRootText("[Sign in]"), "[Sign in]")
                    awaitRootText("Sign in to OpenAI")
                    ports.logins.first().completion.complete(Unit)
                    withTimeout(2_000) { ports.logins.first().cancelled.await() }
                    sendKeyEvent(KeyboardEvent(codepoint = 27))
                    awaitRootText("[Sign in]")
                    root.owner.viewModel.selectPage(SettingsPage.Mcp)
                    mcp.details("server")
                    clickText(awaitRootText("[Log in]"), "[Log in]")
                    withTimeout(2_000) { ports.secondPrepared.await() }
                    opening.complete(Unit)
                    withTimeout(2_000) { secondOpened.await() }
                    assertEquals(2, calls)
                    assertEquals(1, ports.logins.first().cancellations)
                    assertEquals(0, ports.logins.last().cancellations)
                    assertFalse(mcp.state.value.operationFailure)
                }
            }
        }
    }

    test("root renderer removal cancels its exact effect but Login transition retains Settings owner") {
        withSettingsApplication { app ->
            runSettingsRootTest {
                state.size.value = Terminal.Size(120, 40)
                val ports = RootMcpPorts()
                val opening = CompletableDeferred<Unit>()
                val opened = CompletableDeferred<Unit>()
                var visible by mutableStateOf(true)
                withBoundSettings(app, ports) { root, mcp ->
                    setContentAndSnapshot {
                        if (visible) SessionTreeCliScreen(root, app.newLineKey, app.sidebarSettings) {
                            opened.complete(Unit)
                            opening.await()
                            true
                        } else Text("root renderer removed")
                    }
                    mcp.details("server")
                    clickText(awaitRootText("[Log in]"), "[Log in]")
                    withTimeout(2_000) { opened.await() }
                    visible = false
                    awaitRootText("root renderer removed")
                    assertFalse(mcp.state.value.closed)
                    // Whole-renderer removal keeps the baseline effect teardown contract.
                    // It is distinct from Settings -> Login, which keeps the same root mount.
                    withTimeout(2_000) { ports.logins.single().cancelled.await() }
                    assertEquals(1, ports.logins.single().cancellations)
                    visible = true
                    clickText(awaitRootText("[Log in]"), "[Log in]")
                    withTimeout(2_000) { ports.secondPrepared.await() }
                    root.owner.viewModel.selectPage(SettingsPage.OpenAi)
                    clickText(awaitRootText("[Sign in]"), "[Sign in]")
                    awaitRootText("Sign in to OpenAI")
                    assertSame(root.owner, assertIs<ApplicationPopupState.Login>(root.popup.value).returnTo)
                    assertTrue(root.closeTab(root.owner.target))
                    withTimeout(2_000) { mcp.state.first { it.closed } }
                    withTimeout(2_000) { ports.logins.last().cancelled.await() }
                    assertEquals(listOf(1, 1), ports.logins.map { it.cancellations })
                    assertEquals(ApplicationPopupState.Closed, app.viewModel.popup.value)
                }
            }
        }
    }
}

/**
 * Full production SessionTreeCliScreen and real Application popup commands. Only the MCP port is
 * substituted to gate external OAuth; other Settings children, Login factory and exact owner close
 * are real. The display binding maps the real Settings handle once and maps Login.returnTo back to
 * that same handle. It forwards every owner command to its original, never implements a second
 * navigation or Settings state machine.
 */
private class BoundSettingsRoot(
    private val application: ApplicationViewModel,
    val original: ApplicationPopupState.Settings,
    mcp: McpSettingsViewModel,
) : ApplicationViewModel by application {
    private val global = object : GlobalSettingsViewModel by original.viewModel.global {
        override val mcpSettings = mcp
    }
    val owner = ApplicationPopupState.Settings(original.target,
        object : SettingsViewModel by original.viewModel {
            override val global = this@BoundSettingsRoot.global
            override fun selectPage(page: SettingsPage) {
                if (selectedPage.value == SettingsPage.Mcp && page != SettingsPage.Mcp) mcp.hidePage()
                original.viewModel.selectPage(page)
            }
        },
    )
    override val popup = MutableStateFlow<ApplicationPopupState>(owner)
    private var originalLogin: ApplicationPopupState.Login? = null
    private var displayedLogin: ApplicationPopupState.Login? = null
    override suspend fun openLoginPopup(returnTo: ApplicationPopupState.Settings): ApplicationPopupState.Login {
        assertSame(owner, returnTo)
        val actual = application.openLoginPopup(original)
        return ApplicationPopupState.Login(actual.viewModel, owner).also {
            originalLogin = actual
            displayedLogin = it
            popup.value = it
        }
    }
    override fun dismissPopup(expected: ApplicationPopupState.Open): Boolean {
        val actual = when {
            expected === owner -> original
            expected === displayedLogin -> requireNotNull(originalLogin)
            else -> return false
        }
        if (!application.dismissPopup(actual)) return false
        popup.value = if (application.popup.value === original) owner else application.popup.value
        return true
    }
    override suspend fun closeTab(target: SessionViewModel): Boolean =
        application.closeTab(target).also {
            if (application.popup.value === ApplicationPopupState.Closed) {
                popup.value = ApplicationPopupState.Closed
            }
        }
}

private suspend fun withBoundSettings(
    app: KodexApplication,
    ports: RootMcpPorts,
    block: suspend (BoundSettingsRoot, McpSettingsViewModel) -> Unit,
) = coroutineScope {
    val original = app.viewModel.openSettingsPopup(
        requireNotNull(app.viewModel.navigation.value.selected), SettingsPage.Mcp,
    )
    val mcp = createMcpSettingsViewModel(ports, this)
    // The injected child is closed by actual Settings owner closure, not renderer disposal.
    val closure = launch {
        original.viewModel.global.mcpSettings.state.first { it.closed }
        mcp.close()
    }
    try {
        block(BoundSettingsRoot(app.viewModel, original, mcp), mcp)
    } finally {
        withContext(NonCancellable) {
            val current = app.viewModel.popup.value
            if (current is ApplicationPopupState.Login) app.viewModel.dismissPopup(current)
            app.viewModel.dismissPopup(original)
            closure.cancelAndJoin()
            mcp.close()
        }
    }
}

private suspend fun runSettingsRootTest(block: suspend TestMosaic<String>.() -> Unit) = runMosaicTest {
    try { block() } finally { cancel() }
}

private class RootMcpPorts : McpSettingsDependencies {
    override val servers = MutableStateFlow(listOf(McpServerSettingsState(
        "server", McpTransportKind.StreamableHttp, true, McpAuthenticationState.LoginRequired,
        McpServerSettingsStatus.AuthenticationBlocked(McpAuthenticationState.LoginRequired),
    )))
    override val operationFailure = MutableStateFlow(false)
    val preparing = CompletableDeferred<Unit>()
    val secondPrepared = CompletableDeferred<Unit>()
    var prepareGate: CompletableDeferred<Unit>? = null
    val logins = mutableListOf<Attempt>()
    override suspend fun startLogin(serverName: String, interactionScope: CoroutineScope): McpSettingsLogin {
        preparing.complete(Unit)
        prepareGate?.await()
        return Attempt(serverName).also {
            logins += it
            if (logins.size == 2) secondPrepared.complete(Unit)
        }
    }
    class Attempt(override val serverName: String) : McpSettingsLogin {
        override val authorizationUrl = "https://example.invalid/ephemeral-authorization"
        val completion = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        var cancellations = 0
        override suspend fun awaitCompletion() { completion.await() }
        override fun cancel() { cancellations++; cancelled.complete(Unit) }
    }
    override fun captureEditor(serverName: String?): McpEditHandle? = error("No editor expected")
    override fun captureServer(serverName: String): McpServerHandle? = error("No mutation expected")
    override suspend fun readImport(): McpImportHandle = error("No import expected")
    override suspend fun reconnect(serverName: String) = error("No reconnect expected")
    override suspend fun logout(serverName: String) = error("No logout expected")
    override fun reportFailure(failure: Throwable) { operationFailure.value = true }
    override fun dismissFailure() { operationFailure.value = false }
}

private suspend fun TestMosaic<String>.clickText(snapshot: String, label: String) {
    val lines = snapshot.lines()
    val row = lines.indexOfFirst { label in it }
    assertTrue(row >= 0, snapshot)
    val column = lines[row].indexOf(label) + 1
    sendMouseEvent(MouseEvent(column, row, MouseEvent.Type.Press, MouseEvent.Button.Left))
    sendMouseEvent(MouseEvent(column, row, MouseEvent.Type.Release))
    awaitSnapshot()
}

private suspend fun TestMosaic<String>.awaitRootText(expected: String): String {
    var snapshot = ""
    repeat(8) {
        snapshot = try { awaitSnapshot() } catch (_: TimeoutCancellationException) {
            draw().render(AnsiLevel.NONE, supportsKittyUnderlines = false)
        }
        assertFalse("ephemeral-authorization" in snapshot, snapshot)
        if (expected in snapshot) return snapshot
    }
    assertTrue(expected in snapshot, snapshot)
    return snapshot
}

private suspend fun withSettingsApplication(block: suspend (KodexApplication) -> Unit) = coroutineScope {
    val home = Path(SystemTemporaryDirectory, "kodex-settings-root-${Random.nextLong()}")
    val client = mockOpenAiClient {
        listModels { OpenAiResult.Success(ModelsResponse(listOf(ModelInfo(
            OpenAiModelId("test-model"), "Test Model", contextWindow = 100_000, maxContextWindow = 100_000,
        )))) }
        createResponse { flow { testAnswer() } }
    }
    val loginClient = object : OpenAiLoginClient {
        override fun authorizationUrl(request: OpenAiLoginAuthorization) = error("No browser expected")
        override suspend fun exchangeAuthorizationCode(request: OpenAiAuthorizationCodeExchange):
            OpenAiLoginResult<OpenAiSubscriptionTokens> = error("No credential exchange expected")
        override suspend fun refreshSubscriptionTokens(refreshToken: String):
            OpenAiLoginResult<OpenAiSubscriptionTokenRefresh> = error("No credentials expected")
    }
    try {
        val handle = prepareKodexHome(home)
        try {
            withKodexApplication(
                handle, home, Path(home, "codex"), Path(home, "agents"), applicationWidth = 120,
                createClient = { client }, createLoginClient = { loginClient },
            ) { app -> block(app) }
        } finally { withContext(NonCancellable) { handle.closeAndJoin() } }
    } finally { withContext(NonCancellable) { deleteTestDirectory(home) } }
}
