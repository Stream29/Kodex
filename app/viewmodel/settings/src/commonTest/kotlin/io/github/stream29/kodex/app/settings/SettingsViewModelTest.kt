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
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationState
import io.github.stream29.kodex.app.settings.contract.SettingsPage
import io.github.stream29.kodex.app.settings.contract.UsageResetState
import io.github.stream29.kodex.cli.settings.InMemoryKodexGlobalSettings
import io.github.stream29.kodex.cli.settings.KodexGlobalSettings
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
import io.github.stream29.kodex.openai.client.contract.OpenAiAuthStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @Test
    fun updateQueueDrainsAcceptedWritesBeforeClosingItsTarget() = runTest {
        val releaseWrite = CompletableDeferred<Unit>()
        var writeCompleted = false
        var targetClosed = false
        val queue = SettingsUpdateQueue(backgroundScope)

        queue.submit {
            releaseWrite.await()
            writeCompleted = true
        }
        runCurrent()
        queue.close { targetClosed = true }

        assertFalse(writeCompleted)
        assertFalse(targetClosed)

        releaseWrite.complete(Unit)
        runCurrent()

        assertTrue(writeCompleted)
        assertTrue(targetClosed)
    }

    @Test
    fun updateQueueContinuesAfterAFailedWrite() = runTest {
        val releaseFirstWrite = CompletableDeferred<Unit>()
        var secondWriteCompleted = false
        val queue = SettingsUpdateQueue(backgroundScope)

        queue.submit {
            releaseFirstWrite.await()
            error("Failed write")
        }
        queue.submit {
            secondWriteCompleted = true
        }
        runCurrent()
        releaseFirstWrite.complete(Unit)
        runCurrent()

        assertTrue(secondWriteCompleted)

        queue.close()
    }

    @Test
    fun rootSharesGlobalSettingsAuthority() = runTest {
        val settings = InMemoryKodexGlobalSettings(
            KodexGlobalSettings(codexHome = Path("codex-home")),
        )
        val source = TestSessionSettingsDataSource()
        val viewModel = createSettingsViewModel(
            initialPage = SettingsPage.Session,
            globalSettings = settings,
            authentication = TestAuthStore(),
            accountUsage = TestAccountUsageStore(),
            mcpManager = TestMcpManager(),
            hookManager = TestHookManager(),
            models = MutableStateFlow(emptyList<ModelInfo>()),
            sessionSettings = source,
            ownerScope = backgroundScope,
        )
        val global = viewModel.global
        val session = viewModel.session
        val newSession = viewModel.newSession

        assertSame(global, viewModel.global)
        assertSame(session, viewModel.session)
        assertSame(newSession, viewModel.newSession)
        assertEquals(SettingsPage.Session, viewModel.selectedPage.value)

        viewModel.selectPage(SettingsPage.NewSession)
        val defaults = newSession.state.value
        newSession.updateModel(defaults.revision, OpenAiModelId("new-default"))
        runCurrent()
        val withUpdatedModel = newSession.state.value
        newSession.updateRequestUserInputMode(
            withUpdatedModel.revision,
            RequestUserInputMode.NoQuestion,
        )
        runCurrent()

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
        assertEquals(
            settings.settings.value.codexHome,
            global.state.value.codexHome,
        )

        viewModel.close()
        runCurrent()
        assertTrue(source.closed)
    }

    @Test
    fun authenticationProjectionNeverPublishesAccessToken() = runTest {
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
            initialPage = SettingsPage.Global,
            globalSettings = InMemoryKodexGlobalSettings(
                KodexGlobalSettings(codexHome = Path("codex-home")),
            ),
            authentication = auth,
            accountUsage = TestAccountUsageStore(),
            mcpManager = TestMcpManager(),
            hookManager = TestHookManager(),
            models = MutableStateFlow(emptyList()),
            ownerScope = backgroundScope,
        )
        runCurrent()

        val projected = assertIs<SettingsAuthenticationState.Authenticated>(
            viewModel.global.authentication.value,
        )
        assertEquals("person@example.com", projected.email)
        assertEquals("account-id", projected.accountId)
        assertEquals(OpenAiSubscriptionPlan.Pro, projected.planType)
        assertFalse("secret-access-token" in projected.toString())

        viewModel.close()
    }

    @Test
    fun accountUsageProjectionNeverPublishesResetAttemptCredentials() = runTest {
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
            initialPage = SettingsPage.Global,
            globalSettings = InMemoryKodexGlobalSettings(
                KodexGlobalSettings(codexHome = Path("codex-home")),
            ),
            authentication = TestAuthStore(),
            accountUsage = accountUsage,
            mcpManager = TestMcpManager(),
            hookManager = TestHookManager(),
            models = MutableStateFlow(emptyList()),
            ownerScope = backgroundScope,
        )
        runCurrent()

        val projected = assertIs<SettingsAccountUsageState.Redeeming>(
            viewModel.global.accountUsage.value,
        )
        assertSame(snapshot, projected.snapshot)
        assertFalse("private-idempotency-key" in projected.toString())

        viewModel.close()
    }

    @Test
    fun sharedUsageRefreshSurvivesPopupDisposal() = runTest {
        val releaseRefresh = CompletableDeferred<Unit>()
        val accountUsage = TestAccountUsageStore(refreshGate = releaseRefresh)
        val viewModel = createSettingsViewModel(
            initialPage = SettingsPage.Global,
            globalSettings = InMemoryKodexGlobalSettings(
                KodexGlobalSettings(codexHome = Path("codex-home")),
            ),
            authentication = TestAuthStore(),
            accountUsage = accountUsage,
            mcpManager = TestMcpManager(),
            hookManager = TestHookManager(),
            models = MutableStateFlow(emptyList()),
            ownerScope = backgroundScope,
        )
        runCurrent()

        assertEquals(1, accountUsage.refreshCount)
        assertEquals(0, accountUsage.completedRefreshCount)

        viewModel.close()
        releaseRefresh.complete(Unit)
        runCurrent()

        assertEquals(1, accountUsage.completedRefreshCount)
    }

    @Test
    fun globalCodexHomeUsesAnOwnedDirectoryPickerAndPersistsItsSelection() = runTest {
        val initialCodexHome = Path("codex-home")
        val selectedCodexHome = Path("selected-codex-home")
        val settings = InMemoryKodexGlobalSettings(
            KodexGlobalSettings(codexHome = initialCodexHome),
        )
        var pickerInitialDirectory: Path? = null
        lateinit var picker: TestDirectoryPickerViewModel
        val viewModel = createSettingsViewModel(
            initialPage = SettingsPage.Global,
            globalSettings = settings,
            authentication = TestAuthStore(),
            accountUsage = TestAccountUsageStore(),
            mcpManager = TestMcpManager(),
            hookManager = TestHookManager(),
            models = MutableStateFlow(emptyList()),
            createDirectoryPicker = { initialDirectory ->
                pickerInitialDirectory = initialDirectory
                TestDirectoryPickerViewModel(initialDirectory).also { picker = it }
            },
            ownerScope = backgroundScope,
        )

        viewModel.global.requestCodexHome()
        val request = assertNotNull(viewModel.global.codexHomePicker.value)
        assertEquals(initialCodexHome, pickerInitialDirectory)
        assertSame(picker, request.viewModel)

        assertTrue(viewModel.global.selectCodexHome(request, selectedCodexHome))
        assertNull(viewModel.global.codexHomePicker.value)
        assertTrue(picker.closed)
        runCurrent()

        assertEquals(selectedCodexHome, settings.settings.value.codexHome)
        assertEquals(selectedCodexHome, viewModel.global.state.value.codexHome)
        assertFalse(viewModel.global.dismissCodexHomePicker(request))

        viewModel.close()
    }

    @Test
    fun sessionWorkingDirectoryUsesAnOwnedDirectoryPicker() = runTest {
        val source = TestSessionSettingsDataSource()
        val selectedDirectory = Path("selected-workspace")
        var pickerInitialDirectory: Path? = null
        lateinit var picker: TestDirectoryPickerViewModel
        val viewModel = createSettingsViewModel(
            initialPage = SettingsPage.Session,
            globalSettings = InMemoryKodexGlobalSettings(
                KodexGlobalSettings(codexHome = Path("codex-home")),
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
            ownerScope = backgroundScope,
        )
        runCurrent()
        val session = assertIs<SessionSettingsState.Available>(viewModel.session.state.value)

        viewModel.session.requestWorkingDirectory(session.snapshot.revision)
        val request = assertNotNull(viewModel.session.directoryPicker.value)
        assertEquals(Path("workspace"), pickerInitialDirectory)
        assertSame(picker, request.viewModel)

        assertTrue(viewModel.session.selectWorkingDirectory(request, selectedDirectory))
        assertNull(viewModel.session.directoryPicker.value)
        assertTrue(picker.closed)
        runCurrent()

        assertEquals(selectedDirectory, source.current.configuration.workingDirectory)

        viewModel.close()
    }

    @Test
    fun sessionChildRejectsStaleRevisionAndNeverResolvesAnotherTarget() = runTest {
        val source = TestSessionSettingsDataSource()
        val viewModel = createSettingsViewModel(
            initialPage = SettingsPage.Session,
            globalSettings = InMemoryKodexGlobalSettings(
                KodexGlobalSettings(codexHome = Path("codex-home")),
            ),
            authentication = TestAuthStore(),
            accountUsage = TestAccountUsageStore(),
            mcpManager = TestMcpManager(),
            hookManager = TestHookManager(),
            models = MutableStateFlow(emptyList()),
            sessionSettings = source,
            ownerScope = backgroundScope,
        )
        runCurrent()
        val first = assertIs<SessionSettingsState.Available>(viewModel.session.state.value)

        viewModel.session.updateModel(first.snapshot.revision, OpenAiModelId("session-model"))
        runCurrent()
        assertEquals(OpenAiModelId("session-model"), source.current.configuration.model)
        assertEquals(1, source.updateCount)

        val afterModelUpdate = assertIs<SessionSettingsState.Available>(
            viewModel.session.state.value,
        )
        viewModel.session.updateRequestUserInputMode(
            afterModelUpdate.snapshot.revision,
            RequestUserInputMode.NoQuestion,
        )
        runCurrent()
        assertEquals(
            RequestUserInputMode.NoQuestion,
            source.current.configuration.requestUserInputMode,
        )
        assertEquals(2, source.updateCount)

        viewModel.session.requestWorkingDirectory(first.snapshot.revision)

        viewModel.close()
        runCurrent()
    }

    @Test
    fun resetRetryReusesThePreparedIdempotencyAttempt() = runTest {
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
            initialPage = SettingsPage.Global,
            globalSettings = InMemoryKodexGlobalSettings(
                KodexGlobalSettings(codexHome = Path("codex-home")),
            ),
            authentication = TestAuthStore(),
            accountUsage = accountUsage,
            mcpManager = TestMcpManager(),
            hookManager = TestHookManager(),
            models = MutableStateFlow(emptyList()),
            ownerScope = backgroundScope,
        )
        runCurrent()

        viewModel.global.requestUsageReset()
        val choosing = assertIs<UsageResetState.Choosing>(viewModel.global.usageReset.value)
        viewModel.global.selectUsageReset(choosing.request.options.single())
        runCurrent()
        assertIs<UsageResetState.Confirming>(viewModel.global.usageReset.value)

        viewModel.global.confirmUsageReset()
        runCurrent()
        assertIs<UsageResetState.ConsumeFailed>(viewModel.global.usageReset.value)
        viewModel.global.retryUsageReset()
        runCurrent()
        assertIs<UsageResetState.Completed>(viewModel.global.usageReset.value)

        assertEquals(2, accountUsage.consumedAttempts.size)
        assertSame(accountUsage.consumedAttempts[0], accountUsage.consumedAttempts[1])
        assertEquals(1, accountUsage.createdAttempts)

        viewModel.close()
    }

    @Test
    fun mcpProjectionContainsOnlySanitizedLifecycleData() = runTest {
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
            initialPage = SettingsPage.Global,
            globalSettings = InMemoryKodexGlobalSettings(
                KodexGlobalSettings(codexHome = Path("codex-home")),
            ),
            authentication = TestAuthStore(),
            accountUsage = TestAccountUsageStore(),
            mcpManager = manager,
            hookManager = TestHookManager(),
            models = MutableStateFlow(emptyList()),
            ownerScope = backgroundScope,
        )
        runCurrent()

        val row = viewModel.global.mcpServers.value.single()
        assertEquals("private-server", row.serverName)
        assertIs<McpServerSettingsStatus.Failed>(row.status)
        assertFalse("secret" in row.toString())

        viewModel.global.reconnectMcpServer("private-server")
        runCurrent()
        assertEquals(listOf("private-server"), manager.reconnects)

        manager.servers.value = listOf(
            initialServer.copy(
                connection = McpClientState.Healthy,
                toolCount = 3,
            ),
        )
        runCurrent()
        val healthy = assertIs<McpServerSettingsStatus.Healthy>(
            viewModel.global.mcpServers.value.single().status,
        )
        assertEquals(3, healthy.toolCount)
        viewModel.global.reconnectMcpServer("private-server")
        runCurrent()
        assertEquals(listOf("private-server"), manager.reconnects)

        viewModel.close()
    }
}

private class TestAuthStore(
    initialState: OpenAiAuthState = OpenAiAuthState.Unavailable.CredentialsNotFound,
) : OpenAiAuthStore {
    override val state: StateFlow<OpenAiAuthState> = MutableStateFlow(initialState)
}

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
