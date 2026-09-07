package io.github.stream29.kodex.app.settings

import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerEffect
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerLoadState
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerState
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerViewModel
import io.github.stream29.kodex.app.settings.contract.McpServerSettingsStatus
import io.github.stream29.kodex.app.settings.contract.SessionSettingsConfiguration
import io.github.stream29.kodex.app.settings.contract.SessionSettingsDataSource
import io.github.stream29.kodex.app.settings.contract.SessionSettingsDataState
import io.github.stream29.kodex.app.settings.contract.SessionSettingsSnapshot
import io.github.stream29.kodex.app.settings.contract.SessionSettingsState
import io.github.stream29.kodex.app.settings.contract.SessionSettingsTargetKind
import io.github.stream29.kodex.app.settings.contract.SettingsAccountUsageState
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationOperation
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationOperationState
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationState
import io.github.stream29.kodex.app.settings.contract.SettingsPage
import io.github.stream29.kodex.app.settings.contract.UsageResetState
import io.github.stream29.kodex.app.settings.contract.BuiltInContextSource
import io.github.stream29.kodex.cli.auth.KodexAuthLoginAttempt
import io.github.stream29.kodex.cli.auth.KodexAuthStore
import io.github.stream29.kodex.cli.settings.InMemoryKodexGlobalSettings
import io.github.stream29.kodex.cli.settings.KodexAuthSource
import io.github.stream29.kodex.cli.settings.KodexGlobalSettings
import io.github.stream29.kodex.cli.settings.SidebarSettings
import io.github.stream29.kodex.hook.contract.HookDraft
import io.github.stream29.kodex.hook.contract.HookManagedState
import io.github.stream29.kodex.hook.contract.HookManager
import io.github.stream29.kodex.mcp.contract.McpClientFailureReason
import io.github.stream29.kodex.mcp.contract.McpClientState
import io.github.stream29.kodex.mcp.contract.McpAuthenticationState
import io.github.stream29.kodex.mcp.contract.McpImportDecision
import io.github.stream29.kodex.mcp.contract.McpImportPreview
import io.github.stream29.kodex.mcp.contract.McpManagedServerState
import io.github.stream29.kodex.mcp.contract.McpManager
import io.github.stream29.kodex.mcp.contract.McpManagerEffect
import io.github.stream29.kodex.mcp.contract.McpServerDraft
import io.github.stream29.kodex.mcp.contract.McpTransportKind
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.OpenAiAuthState
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.OpenAiSubscriptionAuthState
import io.github.stream29.kodex.openai.OpenAiSubscriptionPlan
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.openai.accountusage.CodexAccountUsageSnapshot
import io.github.stream29.kodex.openai.accountusage.CodexAccountUsageState
import io.github.stream29.kodex.openai.accountusage.CodexAccountUsageStore
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetAttempt
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetCredit
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetCredits
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.io.files.Path
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
val settingsViewModelTest by testSuite {
    test("updateQueueDrainsAcceptedWritesBeforeClosingItsTarget") {
        val releaseWrite = CompletableDeferred<Unit>()
        var writeCompleted = false
        var targetClosed = false
        val queue = SettingsUpdateQueue(testScope.backgroundScope)

        queue.submit {
            releaseWrite.await()
            writeCompleted = true
        }
        testScope.runCurrent()
        queue.close { targetClosed = true }

        assertFalse(writeCompleted)
        assertFalse(targetClosed)

        releaseWrite.complete(Unit)
        testScope.runCurrent()

        assertTrue(writeCompleted)
        assertTrue(targetClosed)
    }

    test("updateQueueContinuesAfterAFailedWrite") {
        val releaseFirstWrite = CompletableDeferred<Unit>()
        var secondWriteCompleted = false
        var reportedError: Throwable? = null
        val queue = SettingsUpdateQueue(testScope.backgroundScope)

        queue.submit(reportError = { reportedError = it }) {
            releaseFirstWrite.await()
            error("Failed write")
        }
        queue.submit {
            secondWriteCompleted = true
        }
        testScope.runCurrent()
        releaseFirstWrite.complete(Unit)
        testScope.runCurrent()

        assertTrue(secondWriteCompleted)
        assertEquals("Failed write", reportedError?.message)

        queue.close()
    }

    test("settingsViewModelRoutesUnhandledErrorWithCwd") {
        val failingSource = object : SessionSettingsDataSource {
            override val state: StateFlow<SessionSettingsDataState> = MutableStateFlow(
                SessionSettingsDataState.Available(initialSnapshot()),
            )
            override suspend fun tryUpdateConfiguration(
                expectedRevision: Long,
                configuration: SessionSettingsConfiguration,
            ): Boolean {
                throw IllegalStateException("source boom")
            }
            override suspend fun tryRenameSession(
                expectedRevision: Long,
                sessionName: String,
            ): Boolean = false
            override fun close(): Unit = Unit
        }

        var capturedError: Throwable? = null
        var capturedCwd: Path? = null

        val viewModel = createSettingsViewModel(
            initialPage = SettingsPage.CurrentSession,
            globalSettings = InMemoryKodexGlobalSettings(KodexGlobalSettings()),
            authentication = TestAuthStore(),
            accountUsage = TestAccountUsageStore(),
            mcpManager = TestMcpManager(),
            hookManager = TestHookManager(),
            models = MutableStateFlow(emptyList()),
            sessionSettings = failingSource,
            ownerScope = testScope.backgroundScope,
            reportUnhandledError = { failure, cwd ->
                capturedError = failure
                capturedCwd = cwd
            },
        )
        testScope.runCurrent()

        viewModel.session.updateModel(0, OpenAiModelId("fail-model"))
        testScope.runCurrent()

        assertEquals("source boom", capturedError?.message)
        assertEquals(Path("workspace"), capturedCwd)

        viewModel.close()
    }

    test("rootSharesGlobalSettingsAuthority") {
        val settings = InMemoryKodexGlobalSettings(
            KodexGlobalSettings(),
        )
        val source = TestSessionSettingsDataSource()
        val viewModel = createSettingsViewModel(
            initialPage = SettingsPage.CurrentSession,
            globalSettings = settings,
            authentication = TestAuthStore(),
            accountUsage = TestAccountUsageStore(),
            mcpManager = TestMcpManager(),
            hookManager = TestHookManager(),
            models = MutableStateFlow(emptyList<ModelInfo>()),
            sessionSettings = source,
            ownerScope = testScope.backgroundScope,
        )
        val global = viewModel.global
        val session = viewModel.session
        val newSession = viewModel.newSession

        assertSame(global, viewModel.global)
        assertSame(session, viewModel.session)
        assertSame(newSession, viewModel.newSession)
        assertEquals(SettingsPage.CurrentSession, viewModel.selectedPage.value)

        viewModel.selectPage(SettingsPage.NewSession)
        val defaults = newSession.state.value
        newSession.updateModel(defaults.revision, OpenAiModelId("new-default"))
        testScope.runCurrent()
        val withUpdatedModel = newSession.state.value
        newSession.updateRequestUserInputMode(
            withUpdatedModel.revision,
            RequestUserInputMode.NoQuestion,
        )
        testScope.runCurrent()

        assertEquals(
            OpenAiModelId("new-default"),
            settings.settings.value.newSession.model,
        )
        assertEquals(
            RequestUserInputMode.NoQuestion,
            settings.settings.value.newSession.requestUserInputMode,
        )
        assertEquals(
            OpenAiModelId("new-default"),
            newSession.state.value.settings.model,
        )
        assertEquals(
            RequestUserInputMode.NoQuestion,
            newSession.state.value.settings.requestUserInputMode,
        )
        global.updateLeftSidebarWidth(36)
        global.updateRightSidebarWidth(19)
        testScope.runCurrent()
        assertEquals(
            SidebarSettings(leftWidth = 36, rightWidth = 19),
            settings.settings.value.sidebars,
        )
        assertEquals(settings.settings.value.sidebars, global.state.value.sidebars)

        viewModel.close()
        testScope.runCurrent()
        assertTrue(source.closed)
    }

    test("contextSourcesSupportBuiltInTogglesAndCustomSourceLifecycle") {
        val settings = InMemoryKodexGlobalSettings(KodexGlobalSettings())
        val viewModel = createSettingsViewModel(
            initialPage = SettingsPage.ContextSources,
            globalSettings = settings,
            authentication = TestAuthStore(),
            accountUsage = TestAccountUsageStore(),
            mcpManager = TestMcpManager(),
            hookManager = TestHookManager(),
            models = MutableStateFlow(emptyList()),
            ownerScope = testScope.backgroundScope,
        )

        viewModel.global.setBuiltInContextSourceEnabled(BuiltInContextSource.CodexHome, false)
        assertEquals(
            "Enter an absolute path, ~, or ~/path.",
            viewModel.global.addCustomContextSource("relative/path"),
        )
        assertEquals(null, viewModel.global.addCustomContextSource("/tmp/kodex-context-source"))
        testScope.runCurrent()

        assertFalse(settings.settings.value.contextSources.codexHomeEnabled)
        assertEquals(
            listOf("/tmp/kodex-context-source"),
            settings.settings.value.contextSources.customSources.map { source -> source.path },
        )

        viewModel.global.setCustomContextSourceEnabled("/tmp/kodex-context-source", false)
        testScope.runCurrent()
        assertFalse(settings.settings.value.contextSources.customSources.single().enabled)

        viewModel.global.removeCustomContextSource("/tmp/kodex-context-source")
        testScope.runCurrent()
        assertTrue(settings.settings.value.contextSources.customSources.isEmpty())

        viewModel.close()
    }

    test("authenticationProjectionNeverPublishesAccessToken") {
        val auth = TestAuthStore(
            OpenAiAuthState.Authenticated(
                OpenAiSubscriptionAuthState(
                    accessToken = "secret-access-token",
                    accountId = "account-id",
                    planType = OpenAiSubscriptionPlan.Pro,
                    email = "person@example.com",
                ),
            ),
        )
        val viewModel = createSettingsViewModel(
            initialPage = SettingsPage.OpenAi,
            globalSettings = InMemoryKodexGlobalSettings(
                KodexGlobalSettings(),
            ),
            authentication = auth,
            accountUsage = TestAccountUsageStore(),
            mcpManager = TestMcpManager(),
            hookManager = TestHookManager(),
            models = MutableStateFlow(emptyList()),
            ownerScope = testScope.backgroundScope,
        )
        testScope.runCurrent()

        val projected = assertIs<SettingsAuthenticationState.Authenticated>(
            viewModel.global.authentication.value,
        )
        assertEquals("person@example.com", projected.email)
        assertEquals("account-id", projected.accountId)
        assertEquals(OpenAiSubscriptionPlan.Pro, projected.planType)
        assertFalse("secret-access-token" in projected.toString())

        viewModel.close()
    }

    test("kodexAuthenticationSupportsReloadAndLogout") {
        val auth = TestAuthStore(authenticatedState())
        val viewModel = createSettingsViewModel(
            initialPage = SettingsPage.OpenAi,
            globalSettings = InMemoryKodexGlobalSettings(
                KodexGlobalSettings(
                    authSource = KodexAuthSource.Kodex,
                ),
            ),
            authentication = auth,
            accountUsage = TestAccountUsageStore(),
            mcpManager = TestMcpManager(),
            hookManager = TestHookManager(),
            models = MutableStateFlow(emptyList()),
            ownerScope = testScope.backgroundScope,
        )
        testScope.runCurrent()

        viewModel.global.reloadAuthentication()
        testScope.runCurrent()
        assertEquals(1, auth.reloadCount)
        assertEquals(
            SettingsAuthenticationOperationState.Idle,
            viewModel.global.authenticationOperation.value,
        )

        viewModel.global.logoutKodex()
        testScope.runCurrent()
        assertEquals(1, auth.logoutCount)
        assertEquals(
            OpenAiAuthState.Unavailable.CredentialsNotFound,
            auth.state.value,
        )
        assertIs<SettingsAuthenticationState.Unavailable>(
            viewModel.global.authentication.value,
        )

        viewModel.close()
    }

    test("authenticationOperationFailureIsTypedAndDismissible") {
        val auth = TestAuthStore(
            initialState = authenticatedState(),
            reloadFailure = IllegalStateException("reload failed"),
        )
        val viewModel = createSettingsViewModel(
            initialPage = SettingsPage.OpenAi,
            globalSettings = InMemoryKodexGlobalSettings(
                KodexGlobalSettings(
                    authSource = KodexAuthSource.Kodex,
                ),
            ),
            authentication = auth,
            accountUsage = TestAccountUsageStore(),
            mcpManager = TestMcpManager(),
            hookManager = TestHookManager(),
            models = MutableStateFlow(emptyList()),
            ownerScope = testScope.backgroundScope,
        )

        viewModel.global.reloadAuthentication()
        testScope.runCurrent()

        assertEquals(
            SettingsAuthenticationOperationState.Failed(
                SettingsAuthenticationOperation.Reload,
            ),
            viewModel.global.authenticationOperation.value,
        )
        viewModel.global.dismissAuthenticationOperationFailure()
        assertEquals(
            SettingsAuthenticationOperationState.Idle,
            viewModel.global.authenticationOperation.value,
        )

        viewModel.close()
    }

    test("accountUsageProjectionNeverPublishesResetAttemptCredentials") {
        val snapshot = usageSnapshot()
        val accountUsage = TestAccountUsageStore(
            initialState = CodexAccountUsageState.Redeeming(
                snapshot = snapshot,
                attempt = CodexRateLimitResetAttempt(
                    idempotencyKey = "private-idempotency-key",
                    creditId = "credit-1",
                ),
            ),
        )
        val viewModel = createSettingsViewModel(
            initialPage = SettingsPage.OpenAi,
            globalSettings = InMemoryKodexGlobalSettings(
                KodexGlobalSettings(),
            ),
            authentication = TestAuthStore(),
            accountUsage = accountUsage,
            mcpManager = TestMcpManager(),
            hookManager = TestHookManager(),
            models = MutableStateFlow(emptyList()),
            ownerScope = testScope.backgroundScope,
        )
        testScope.runCurrent()

        val projected = assertIs<SettingsAccountUsageState.Redeeming>(
            viewModel.global.accountUsage.value,
        )
        assertSame(snapshot, projected.snapshot)
        assertFalse("private-idempotency-key" in projected.toString())

        viewModel.close()
    }

    test("sharedUsageRefreshSurvivesPopupDisposal") {
        val releaseRefresh = CompletableDeferred<Unit>()
        val accountUsage = TestAccountUsageStore(refreshGate = releaseRefresh)
        val viewModel = createSettingsViewModel(
            initialPage = SettingsPage.OpenAi,
            globalSettings = InMemoryKodexGlobalSettings(
                KodexGlobalSettings(),
            ),
            authentication = TestAuthStore(),
            accountUsage = accountUsage,
            mcpManager = TestMcpManager(),
            hookManager = TestHookManager(),
            models = MutableStateFlow(emptyList()),
            ownerScope = testScope.backgroundScope,
        )
        testScope.runCurrent()

        assertEquals(1, accountUsage.refreshCount)
        assertEquals(0, accountUsage.completedRefreshCount)

        viewModel.close()
        releaseRefresh.complete(Unit)
        testScope.runCurrent()

        assertEquals(1, accountUsage.completedRefreshCount)
    }

    test("sessionWorkingDirectoryUsesAnOwnedDirectoryPicker") {
        val source = TestSessionSettingsDataSource()
        val selectedDirectory = Path("selected-workspace")
        var pickerInitialDirectory: Path? = null
        lateinit var picker: TestDirectoryPickerViewModel
        val viewModel = createSettingsViewModel(
            initialPage = SettingsPage.CurrentSession,
            globalSettings = InMemoryKodexGlobalSettings(
                KodexGlobalSettings(),
            ),
            authentication = TestAuthStore(),
            accountUsage = TestAccountUsageStore(),
            mcpManager = TestMcpManager(),
            hookManager = TestHookManager(),
            models = MutableStateFlow(emptyList()),
            sessionSettings = source,
            createDirectoryPicker = { initialDirectory ->
                pickerInitialDirectory = initialDirectory
                TestDirectoryPickerViewModel(initialDirectory).also { picker = it }
            },
            ownerScope = testScope.backgroundScope,
        )
        testScope.runCurrent()
        val session = assertIs<SessionSettingsState.Available>(viewModel.session.state.value)

        viewModel.session.requestWorkingDirectory(session.snapshot.revision)
        val request = assertNotNull(viewModel.session.directoryPicker.value)
        assertEquals(Path("workspace"), pickerInitialDirectory)
        assertSame(picker, request.viewModel)

        assertTrue(viewModel.session.selectWorkingDirectory(request, selectedDirectory))
        assertNull(viewModel.session.directoryPicker.value)
        assertTrue(picker.closed)
        testScope.runCurrent()

        assertEquals(selectedDirectory, source.current.configuration.workingDirectory)

        viewModel.close()
    }

    test("sessionChildRejectsStaleRevisionAndNeverResolvesAnotherTarget") {
        val source = TestSessionSettingsDataSource()
        val viewModel = createSettingsViewModel(
            initialPage = SettingsPage.CurrentSession,
            globalSettings = InMemoryKodexGlobalSettings(
                KodexGlobalSettings(),
            ),
            authentication = TestAuthStore(),
            accountUsage = TestAccountUsageStore(),
            mcpManager = TestMcpManager(),
            hookManager = TestHookManager(),
            models = MutableStateFlow(emptyList()),
            sessionSettings = source,
            ownerScope = testScope.backgroundScope,
        )
        testScope.runCurrent()
        val first = assertIs<SessionSettingsState.Available>(viewModel.session.state.value)

        viewModel.session.updateModel(first.snapshot.revision, OpenAiModelId("session-model"))
        testScope.runCurrent()
        assertEquals(OpenAiModelId("session-model"), source.current.configuration.model)
        assertEquals(1, source.updateCount)

        val afterModelUpdate = assertIs<SessionSettingsState.Available>(
            viewModel.session.state.value,
        )
        viewModel.session.updateRequestUserInputMode(
            afterModelUpdate.snapshot.revision,
            RequestUserInputMode.NoQuestion,
        )
        testScope.runCurrent()
        assertEquals(
            RequestUserInputMode.NoQuestion,
            source.current.configuration.requestUserInputMode,
        )
        assertEquals(2, source.updateCount)

        viewModel.session.requestWorkingDirectory(first.snapshot.revision)

        viewModel.close()
        testScope.runCurrent()
    }

    test("resetRetryReusesThePreparedIdempotencyAttempt") {
        val accountUsage = TestAccountUsageStore(
            initialState = CodexAccountUsageState.Available(usageSnapshot()),
            consumeResults = ArrayDeque(
                listOf(
                    Result.failure(IllegalStateException("transport")),
                    Result.success(CodexRateLimitResetOutcome.Reset),
                ),
            ),
        )
        val viewModel = createSettingsViewModel(
            initialPage = SettingsPage.OpenAi,
            globalSettings = InMemoryKodexGlobalSettings(
                KodexGlobalSettings(),
            ),
            authentication = TestAuthStore(),
            accountUsage = accountUsage,
            mcpManager = TestMcpManager(),
            hookManager = TestHookManager(),
            models = MutableStateFlow(emptyList()),
            ownerScope = testScope.backgroundScope,
        )
        testScope.runCurrent()

        viewModel.global.requestUsageReset()
        val choosing = assertIs<UsageResetState.Choosing>(viewModel.global.usageReset.value)
        viewModel.global.selectUsageReset(choosing.request.options.single())
        testScope.runCurrent()
        assertIs<UsageResetState.Confirming>(viewModel.global.usageReset.value)

        viewModel.global.confirmUsageReset()
        testScope.runCurrent()
        assertIs<UsageResetState.ConsumeFailed>(viewModel.global.usageReset.value)
        viewModel.global.retryUsageReset()
        testScope.runCurrent()
        assertIs<UsageResetState.Completed>(viewModel.global.usageReset.value)

        assertEquals(2, accountUsage.consumedAttempts.size)
        assertSame(accountUsage.consumedAttempts[0], accountUsage.consumedAttempts[1])
        assertEquals(1, accountUsage.createdAttempts)

        viewModel.close()
    }

    test("mcpProjectionContainsOnlySanitizedLifecycleData") {
        val initialServer = McpManagedServerState(
            serverName = "private-server",
            transport = McpTransportKind.StreamableHttp,
            enabled = true,
            authentication = McpAuthenticationState.NotConfigured,
            connection = McpClientState.Failed(McpClientFailureReason.ConnectionLost),
            toolCount = 0,
            headerNames = listOf("Authorization"),
        )
        val manager = TestMcpManager(listOf(initialServer))
        val viewModel = createSettingsViewModel(
            initialPage = SettingsPage.Mcp,
            globalSettings = InMemoryKodexGlobalSettings(
                KodexGlobalSettings(),
            ),
            authentication = TestAuthStore(),
            accountUsage = TestAccountUsageStore(),
            mcpManager = manager,
            hookManager = TestHookManager(),
            models = MutableStateFlow(emptyList()),
            ownerScope = testScope.backgroundScope,
        )
        testScope.runCurrent()

        val row = viewModel.global.mcpServers.value.single()
        assertEquals("private-server", row.serverName)
        assertIs<McpServerSettingsStatus.Failed>(row.status)
        assertFalse("secret" in row.toString())

        viewModel.global.reconnectMcpServer("private-server")
        testScope.runCurrent()
        assertEquals(listOf("private-server"), manager.reconnects)

        manager.servers.value = listOf(
            initialServer.copy(
                connection = McpClientState.Healthy,
                toolCount = 3,
            ),
        )
        testScope.runCurrent()
        val healthy = assertIs<McpServerSettingsStatus.Healthy>(
            viewModel.global.mcpServers.value.single().status,
        )
        assertEquals(3, healthy.toolCount)
        viewModel.global.reconnectMcpServer("private-server")
        testScope.runCurrent()
        assertEquals(listOf("private-server"), manager.reconnects)

        viewModel.close()
    }
}

private class TestAuthStore(
    initialState: OpenAiAuthState = OpenAiAuthState.Unavailable.CredentialsNotFound,
    private val reloadFailure: Throwable? = null,
    private val logoutFailure: Throwable? = null,
) : KodexAuthStore {
    private val mutableState = MutableStateFlow(initialState)
    override val state: StateFlow<OpenAiAuthState> = mutableState
    var reloadCount: Int = 0
    var logoutCount: Int = 0

    override suspend fun reload() {
        reloadCount += 1
        reloadFailure?.let { throw it }
    }

    override suspend fun startKodexLogin(): KodexAuthLoginAttempt =
        error("Browser sign-in is not expected in this test.")

    override suspend fun logoutKodex() {
        logoutCount += 1
        logoutFailure?.let { throw it }
        mutableState.value = OpenAiAuthState.Unavailable.CredentialsNotFound
    }

    override fun close(): Unit = Unit
}

private fun authenticatedState(): OpenAiAuthState =
    OpenAiAuthState.Authenticated(
        OpenAiSubscriptionAuthState(
            accessToken = "test-access-token",
            accountId = "account-id",
            planType = OpenAiSubscriptionPlan.Pro,
            email = "person@example.com",
        ),
    )

private class TestDirectoryPickerViewModel(initialDirectory: Path) : DirectoryPickerViewModel {
    override val state: StateFlow<DirectoryPickerState> = MutableStateFlow(
        DirectoryPickerState(
            loadState = DirectoryPickerLoadState.Ready(
                requestId = 1,
                requestedDirectory = initialDirectory,
                directory = initialDirectory,
                children = emptyList(),
            ),
        ),
    )
    override val effects: Flow<DirectoryPickerEffect> = emptyFlow()
    var closed: Boolean = false

    override fun navigateTo(directory: Path): Unit = Unit
    override fun navigateUp(): Unit = Unit
    override fun updateFilter(query: String): Unit = Unit
    override fun clearFilter(): Unit = Unit
    override fun retry(): Unit = Unit
    override fun confirm(): Unit = Unit

    override fun close() {
        closed = true
    }
}

private class TestAccountUsageStore(
    initialState: CodexAccountUsageState = CodexAccountUsageState.Unavailable,
    private val consumeResults: ArrayDeque<Result<CodexRateLimitResetOutcome>> = ArrayDeque(),
    private val refreshGate: CompletableDeferred<Unit>? = null,
) : CodexAccountUsageStore {
    override val state: StateFlow<CodexAccountUsageState> = MutableStateFlow(initialState)
    var refreshCount: Int = 0
    var completedRefreshCount: Int = 0
    var createdAttempts: Int = 0
    val consumedAttempts: MutableList<CodexRateLimitResetAttempt> = mutableListOf()

    override suspend fun refresh() {
        refreshCount += 1
        refreshGate?.await()
        completedRefreshCount += 1
    }

    override suspend fun createResetAttempt(creditId: String?): CodexRateLimitResetAttempt {
        createdAttempts += 1
        return CodexRateLimitResetAttempt(
            idempotencyKey = "attempt-$createdAttempts",
            creditId = creditId,
        )
    }

    override suspend fun consumeResetAttempt(
        attempt: CodexRateLimitResetAttempt,
    ): CodexRateLimitResetOutcome {
        consumedAttempts += attempt
        return consumeResults.removeFirst().getOrThrow()
    }

    override fun close(): Unit = Unit
}

private class TestMcpManager(
    initialServers: List<McpManagedServerState> = emptyList(),
) : McpManager {
    override val servers = MutableStateFlow(initialServers)
    override val effects: Flow<McpManagerEffect> = emptyFlow()
    val reconnects = mutableListOf<String>()

    override suspend fun add(draft: McpServerDraft): Unit = Unit

    override suspend fun edit(
        existingServerName: String,
        draft: McpServerDraft,
    ): Unit = Unit

    override suspend fun delete(serverName: String): Unit = Unit

    override suspend fun setEnabled(
        serverName: String,
        enabled: Boolean,
    ): Unit = Unit

    override suspend fun login(serverName: String): Unit = Unit
    override suspend fun cancelLogin(serverName: String): Unit = Unit
    override suspend fun logout(serverName: String): Unit = Unit

    override suspend fun reconnect(serverName: String) {
        val server = servers.value.singleOrNull { it.serverName == serverName } ?: return
        if (server.connection !is McpClientState.Healthy) reconnects += serverName
    }

    override suspend fun previewCodexImport(filter: String): McpImportPreview =
        McpImportPreview(
            id = 1,
            filter = filter,
            items = emptyList(),
        )

    override suspend fun applyCodexImport(
        previewId: Long,
        decisions: Map<String, McpImportDecision>,
    ): Unit = Unit

    override fun close(): Unit = Unit
}

private class TestHookManager : HookManager {
    override val hooks = MutableStateFlow<List<HookManagedState>>(emptyList())

    override suspend fun add(draft: HookDraft): String = draft.name
    override suspend fun edit(name: String, draft: HookDraft): Unit = Unit
    override suspend fun delete(name: String): Unit = Unit
    override fun editorDraft(name: String): HookDraft? = null

    override fun close(): Unit = Unit
}

private class TestSessionSettingsDataSource : SessionSettingsDataSource {
    private val mutableState = MutableStateFlow<SessionSettingsDataState>(
        SessionSettingsDataState.Available(initialSnapshot()),
    )
    override val state: StateFlow<SessionSettingsDataState> = mutableState
    var updateCount: Int = 0
    var closed: Boolean = false

    val current: SessionSettingsSnapshot
        get() = assertIs<SessionSettingsDataState.Available>(mutableState.value).snapshot

    override suspend fun tryUpdateConfiguration(
        expectedRevision: Long,
        configuration: SessionSettingsConfiguration,
    ): Boolean {
        val current = current
        if (current.revision != expectedRevision || !current.editable) {
            return false
        }
        updateCount += 1
        mutableState.value = SessionSettingsDataState.Available(
            current.copy(
                revision = current.revision + 1,
                configuration = configuration,
            ),
        )
        return true
    }

    override suspend fun tryRenameSession(
        expectedRevision: Long,
        sessionName: String,
    ): Boolean {
        val current = current
        if (current.revision != expectedRevision) {
            return false
        }
        mutableState.value = SessionSettingsDataState.Available(
            current.copy(
                revision = current.revision + 1,
                sessionName = sessionName,
            ),
        )
        return true
    }

    override fun close() {
        closed = true
    }
}

private fun initialSnapshot(): SessionSettingsSnapshot =
    SessionSettingsSnapshot(
        revision = 0,
        targetKind = SessionSettingsTargetKind.MaterializedSession,
        sessionName = "Fixed session",
        configuration = SessionSettingsConfiguration(
            model = OpenAiModelId("initial-model"),
            workingDirectory = Path("workspace"),
            reasoningEffort = ReasoningEffort.Medium,
            serviceTier = ServiceTier.Default,
            requestUserInputMode = RequestUserInputMode.AskUser,
        ),
        editable = true,
    )

private fun usageSnapshot(): CodexAccountUsageSnapshot =
    CodexAccountUsageSnapshot(
        rateLimits = emptyList(),
        resetCredits = CodexRateLimitResetCredits(
            availableCount = 1,
            credits = listOf(
                CodexRateLimitResetCredit(
                    id = "credit-1",
                    grantedAt = null,
                    expiresAt = null,
                    title = "One reset",
                ),
            ),
        ),
        fetchedAt = Instant.parse("2026-08-11T00:00:00Z"),
    )
