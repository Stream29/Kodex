@file:Suppress("DEPRECATION")

package io.github.stream29.kodex.rpc.server

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationState
import io.github.stream29.kodex.app.settings.contract.SettingsAccountUsageState
import io.github.stream29.kodex.agentcontext.contract.ContextPathEvaluation
import io.github.stream29.kodex.cli.settings.KodexAuthSource
import io.github.stream29.kodex.cli.settings.KodexNewSessionSettings
import io.github.stream29.kodex.cli.settings.SessionTitleSettings
import io.github.stream29.kodex.cli.sessiontitle.OpenAiSessionTitleGenerator
import io.github.stream29.kodex.cli.sessiontitle.SessionTitleGenerator
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetOutcome
import io.github.stream29.kodex.rpc.client.RestoringRpcClient
import io.github.stream29.kodex.rpc.contract.*
import io.github.stream29.kodex.rpc.inmemory.withInMemoryRpc
import io.github.stream29.kodex.rpc.models.*
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.rpcexception.*
import io.github.stream29.kodex.utils.sqlite.openSqliteConnection
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.rpc.withService
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

val backendServicesTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("real JSON path RPCs use captured backend base and actual built-ins without acquiring a Session") {
        withServices { services, _ ->
            val roots = services.settings.contextSettings.value
            val base = Path(roots.kodexHome, "captured-base")
            SystemCoroutineFileSystem.createDirectories(Path(base, "B"))
            SystemCoroutineFileSystem.createDirectories(Path(base, "a"))
            SystemCoroutineFileSystem.writeString(Path(base, "not-a-directory"), "private fixture contents")
            val database = Path(roots.kodexHome, "kodex.db")
            assertNull(SystemCoroutineFileSystem.metadataOrNull(database))
            val repository = Path(roots.kodexHome, "sessions")
            assertNull(SystemCoroutineFileSystem.metadataOrNull(repository))
            withInMemoryRpc(services::register) { raw ->
                val global = RestoringRpcClient(raw).withService<GlobalRpc>()
                val selected = assertIs<DirectoryValidationResult.Directory>(
                    global.validateDirectory(DirectoryQuery(".", base.toString())),
                )
                val resolved = SystemCoroutineFileSystem.resolve(base).toString()
                assertEquals(resolved, selected.path)
                val listing = assertIs<DirectoryListResult.Directories>(
                    global.listDirectories(DirectoryQuery(".", base.toString())),
                )
                assertEquals(resolved, listing.directory)
                assertEquals(listOf("a", "B"), listing.children.map { it.name })
                assertEquals(listOf(Path(Path(resolved), "a").toString(), Path(Path(resolved), "B").toString()),
                    listing.children.map { it.path })
                val failure = assertIs<DirectoryValidationResult.Failure>(
                    global.validateDirectory(DirectoryQuery("not-a-directory", base.toString())),
                )
                assertEquals(DirectoryQueryFailure.NotDirectory, failure.reason)
                val invalidBase = assertIs<DirectoryListResult.Failure>(
                    global.listDirectories(DirectoryQuery(".", "relative-base")),
                )
                assertEquals(DirectoryQueryFailure.InvalidLaunchBase, invalidBase.reason)
                val evaluation = assertIs<ContextPathEvaluation.Evaluated>(
                    global.evaluateContextPaths(ContextPathQuery(
                        roots.agentsHome.toString(), listOf(roots.agentsHome.toString(), "relative", "\$HOME"),
                        base.toString(),
                    )),
                )
                assertTrue(evaluation.candidate.matchesBuiltIn)
                assertEquals(roots.agentsHome.toString(), evaluation.candidate.lexicalKey)
                assertEquals(listOf(roots.agentsHome.toString(), null, null),
                    evaluation.originalPaths.map { it.lexicalKey })
                assertNull(SystemCoroutineFileSystem.metadataOrNull(database))
                assertNull(SystemCoroutineFileSystem.metadataOrNull(repository),
                    "Read-only paths must not open the Session repository.")
            }
        }
    }
    test("real JSON GlobalRpc creation uses backend defaults despite an intentionally stale frontend snapshot") {
        withServices { services, _ ->
            withInMemoryRpc(services::register) { raw ->
                val client = RestoringRpcClient(raw)
                val global = client.withService<GlobalRpc>()
                val storage = client.withService<AgentStorageRpc>()
                val staleFrontend = global.getSettings()
                val firstCwd = Path("/fixture/旧目录 with spaces")
                val first = global.createSession(firstCwd)
                val firstNonce = storage.getCacheNonce(first)
                val original = storage.getLastCommitsIn(first, firstNonce, 0, 0, AgentCommitType.SettingsTypes, 1).single().commit as KodexAgentSettings
                val defaults = KodexNewSessionSettings(
                    model = OpenAiModelId("backend-only-model"),
                    reasoningEffort = ReasoningEffort.High,
                    serviceTier = ServiceTier.Ultrafast,
                    requestUserInputMode = RequestUserInputMode.NoQuestion,
                )
                // Another backend-side edit has arrived. Deliberately do not subscribe or
                // refresh the frontend snapshot before sending the cwd-only wire request.
                assertTrue(services.global.compareAndSetSettings(
                    staleFrontend, staleFrontend.copy(newSession = defaults),
                ))
                assertNotEquals(defaults, staleFrontend.newSession)
                val cwd = Path("/fixture/新目录 with spaces")
                val second = global.createSession(cwd)
                val current = storage.getLastCommitsIn(second, storage.getCacheNonce(second), 0, 0, AgentCommitType.SettingsTypes, 1).single().commit as KodexAgentSettings
                assertEquals(defaults.model, current.model)
                assertEquals(defaults.reasoningEffort, current.reasoning.effort)
                assertEquals(defaults.serviceTier, current.serviceTier)
                assertEquals(defaults.requestUserInputMode, current.requestUserInputMode)
                assertEquals(cwd, current.cwd)
                assertEquals("Session $second", current.threadName)
                assertNotEquals(original.turnId, current.turnId)
                assertNotEquals(original.windowId, current.windowId)
                assertEquals(0, services.runtime.getLatestIndex(second))
                assertFalse(services.runtime.getRunningTurn(second))
                assertEquals(firstCwd, original.cwd)
                assertEquals(staleFrontend.newSession.model, original.model)
                assertEquals(original, storage.getLastCommitsIn(first, firstNonce, 0, 0, AgentCommitType.SettingsTypes, 1).single().commit as KodexAgentSettings)
            }
        }
    }

    test("an existing RPC connection cannot create or write after business admission closes") {
        var admitted = true
        var rejected = 0
        withServices(checkAdmission = {
            if (!admitted) { rejected++; error("Backend is stopping.") }
        }) { services, _ ->
            withInMemoryRpc(services::register) { raw ->
                val global = RestoringRpcClient(raw).withService<GlobalRpc>()
                val before = global.getSettings()
                val front = global.getFrontendSettings()
                val index = global.createSession(Path("."))
                admitted = false // The graph/transport are deliberately still alive.
                assertTrue(coroutineContext[Job]!!.isActive)
                // Unknown RPC failures do not promise a concrete Exception type on Native.
                assertTrue(runCatching { global.createSession(Path(".")) }.isFailure)
                assertTrue(runCatching { global.keepSessionAlive(index) }.isFailure)
                assertTrue(runCatching { global.compareAndSetSettings(before, before.copy(daemonTtlSeconds = -1)) }.isFailure)
                assertTrue(runCatching { global.compareAndSetFrontendSettings(front, front.copy(newLineKey = io.github.stream29.kodex.cli.settings.NewLineKey.Enter)) }.isFailure)
                assertTrue(runCatching { global.validateDirectory(DirectoryQuery(".", ".")) }.isFailure)
                assertTrue(runCatching { global.listDirectories(DirectoryQuery(".", ".")) }.isFailure)
                assertTrue(runCatching { global.evaluateContextPaths(ContextPathQuery("/", emptyList(), ".")) }.isFailure)
                assertTrue(runCatching { global.abandonPendingOAuthLogin(Long.MAX_VALUE) }.isFailure)
                assertEquals(8, rejected)
                assertEquals(before, services.settings.unified.current.backend)
                assertEquals(front, services.settings.unified.current.frontend)
            }
        }
    }
    test("the complete backend registers all eight real services and retains independent views") {
        withServices { services, _ ->
            withInMemoryRpc(services::register) { raw ->
                val client = RestoringRpcClient(raw)
                val global = client.withService<GlobalRpc>()
                val runtime = client.withService<AgentRuntimeRpc>()
                val settings = global.getSettings()
                assertTrue(global.compareAndSetSettings(settings, settings))
                assertTrue(global.compareAndSetSettings(settings, settings.copy(sessionTitle = SessionTitleSettings(true))))
                assertFalse(global.compareAndSetSettings(settings, settings))
                assertEquals(emptyList(), global.getSessionCatalog(false))
                val index = global.createSession(Path("."))
                global.keepSessionAlive(index)
                assertEquals(0, runtime.getLatestIndex(index))
                assertTrue(global.getSessionCatalog(false).single().isActive)
                val storage = client.withService<AgentStorageRpc>()
                val nonce = storage.getCacheNonce(index)
                assertEquals(nonce, storage.getCacheNonceFlow(index).first())
                for (types in listOf(
                    AgentCommitType.IndexTypes,
                    AgentCommitType.WorkTypes,
                    AgentCommitType.SettingsTypes,
                    AgentCommitType.TimestampTypes,
                    AgentCommitType.TokenCountTypes,
                    AgentCommitType.UnstableTypes,
                )) {
                    storage.getFirstCommitsIn(index, nonce, 0, 1, types)
                }
                global.archiveSession(index)
                assertTrue(global.getSessionCatalog(false).isEmpty())
                assertTrue(global.getSessionCatalog(true).single().archived)
                global.unarchiveSession(index)
                val copy = global.forkSession(index)
                assertNotEquals(index, copy)
                assertTrue(global.deleteSession(copy))
                assertTrue(global.deleteSession(index))
                assertFalse(global.deleteSession(index))
                assertFailsWith<SessionNotFound> { global.keepSessionAlive(index) }
                assertFailsWith<SessionNotActive> { runtime.getLatestIndex(index) }
            }
        }
    }

    test("session RPC mutations commit to SQLite and mirror files while settings stay in settings.yml") {
        withServices { services, _ ->
            val home = services.settings.contextSettings.value.kodexHome
            val fs = SystemCoroutineFileSystem
            suspend fun mirrored(predicate: suspend () -> Boolean) {
                withTimeout(10.seconds) { while (!predicate()) delay(10) }
            }
            withInMemoryRpc(services::register) { raw ->
                val client = RestoringRpcClient(raw)
                val global = client.withService<GlobalRpc>()
                val runtime = client.withService<AgentRuntimeRpc>()
                val before = global.getSettings()
                assertTrue(global.compareAndSetSettings(before, before.copy(daemonTtlSeconds = 123)))
                val settingsFile = Path(home, "settings.yml")
                assertTrue(fs.readString(settingsFile).contains("123"))
                val index = global.createSession(Path("."))
                openSqliteConnection(Path(home, "kodex.db"), readOnly = true).use { db ->
                    assertEquals(listOf(listOf(index.toLong())), db.query("SELECT session_id FROM sessions"))
                    val state = runtime.appendUserMessage(index, listOf(ContentItem.InputText("SQLite mutation")))
                    val payload = db.query("""SELECT payload_json FROM agent_commits WHERE agent_id = ? AND type = 'user_message' AND "index" = ?""",
                        listOf(index.toLong(), state.toLong())).single().single() as String
                    assertTrue(payload.contains("SQLite mutation"))
                    val message = Path(home, "sessions", index.toString(), "index", "$state.json")
                    mirrored { fs.metadataOrNull(message) != null && fs.readString(message).contains("SQLite mutation") }
                    global.archiveSession(index)
                    assertEquals(listOf(listOf(1L)), db.query("SELECT archived FROM sessions"))
                    val marker = Path(home, "sessions", index.toString(), "archive.mark")
                    mirrored { fs.metadataOrNull(marker) != null }
                    global.unarchiveSession(index)
                    mirrored { fs.metadataOrNull(marker) == null }
                    val fork = global.forkSession(index)
                    assertEquals(listOf(listOf(2L)), db.query("SELECT count(*) FROM sessions"))
                    val forkMessage = Path(home, "sessions", fork.toString(), "index", "$state.json")
                    mirrored { fs.metadataOrNull(forkMessage) != null && fs.readString(forkMessage).contains("SQLite mutation") }
                    assertTrue(fs.readString(forkMessage).contains("SQLite mutation"))
                    assertTrue(global.deleteSession(fork))
                    assertTrue(global.deleteSession(index))
                    assertEquals(listOf(listOf(0L)), db.query("SELECT count(*) FROM sessions"))
                    assertEquals(listOf(listOf(0L)), db.query("SELECT count(*) FROM agent_commits"))
                    mirrored { fs.metadataOrNull(Path(home, "sessions", index.toString())) == null &&
                        fs.metadataOrNull(Path(home, "sessions", fork.toString())) == null }
                    assertEquals(123, global.getSettings().daemonTtlSeconds.toInt())
                    assertTrue(fs.readString(settingsFile).contains("123"))
                    assertTrue(db.query("SELECT name FROM sqlite_master WHERE name = 'global_settings'").isEmpty())
                }
            }
        }
    }

    test("full-service MCP observes value updates without Replace and reads explicit import source") {
        withServices { services, _ ->
            withInMemoryRpc(services::register) { raw ->
                val global = RestoringRpcClient(raw).withService<GlobalRpc>()
                val old = global.getSettings()
                val update = old.copy(mcpServers = mapOf(
                    "disabled" to McpServerConfiguration.StreamableHttp("https://example.invalid/mcp", enabled = false),
                ))
                assertTrue(global.compareAndSetSettings(old, update))
                val snapshot = global.getMcpServersFlow().first { it.isNotEmpty() }
                assertEquals(1, snapshot.size)
                assertTrue(global.compareAndSetSettings(update, update))
                assertEquals(emptyList(), global.getCodexMcpSettings())
                assertFailsWith<Throwable> { global.reconnectMcpServer("missing") }
                assertFailsWith<Throwable> { global.logoutMcpServer("missing") }
            }
        }
    }

    test("accepted Agent output produces a global notification independently of tab observations") {
        withServices { services, _ ->
            withInMemoryRpc(services::register) { raw ->
                val client = RestoringRpcClient(raw)
                val global = client.withService<GlobalRpc>()
                val runtime = client.withService<AgentRuntimeRpc>()
                val index = global.createSession(Path("."))
                val event = async(start = CoroutineStart.UNDISPATCHED) { global.getNotificationFlow().first() }
                (services.runtime as BackendAgentRuntimeRpc).notifications.subscriptionCount.first { it > 0 }
                runtime.appendUserMessage(index, listOf(ContentItem.InputText("question")))
                runtime.resume(index)
                val stop = assertIs<Notification.Stop.AssistantMessage>(event.await())
                assertEquals(index, stop.sessionIndex)
                assertEquals("answer", stop.message.content.filterIsInstance<ContentItem.OutputText>().single().text)
            }
        }
    }
}

internal suspend fun withServices(
    titleGenerator: SessionTitleGenerator? = null,
    checkAdmission: () -> Unit = {},
    block: suspend CoroutineScope.(BackendServices, ServicesTestLogin) -> Unit,
) = withTimeout(30.seconds) {
    val tempDir = if (SystemTemporaryDirectory.isAbsolute) {
        SystemTemporaryDirectory
    } else {
        val fallback = if (Path("C:\\").isAbsolute) Path("C:\\Temp") else Path("/tmp")
        Path(fallback, SystemTemporaryDirectory.toString())
    }
    val root = Path(tempDir, "kodex-rpc-services-${Random.nextLong()}")
    val login = ServicesTestLogin()
    var clientClosed = false
    val mock = mockOpenAiClient {
        listModels { OpenAiResult.Success(ModelsResponse(listOf(ModelInfo(
            OpenAiModelId("test-model"), "Test Model", contextWindow = 100_000, maxContextWindow = 100_000,
        )))) }
        getCodexAccountUsage { OpenAiResult.Success(CodexAccountUsageResponse()) }
        listCodexRateLimitResetCredits { OpenAiResult.Success(CodexRateLimitResetCreditsResponse(availableCount = 1)) }
        getCodexTokenUsageProfile { OpenAiResult.Success(CodexTokenUsageProfile()) }
        consumeCodexRateLimitResetCredit { request, _ ->
            assertEquals("specific", request.creditId)
            OpenAiResult.Success(CodexRateLimitResetConsumeResponse(CodexRateLimitResetConsumeCode.NoCredit))
        }
        createResponse { flow {
            emit(ResponsesStreamEvent.OutputItemDone(0, ResponseItem.Message(
                id = ResponseItemId("message"), role = MessageRole.Assistant,
                content = listOf(ContentItem.OutputText("answer")),
            )))
            emit(ResponsesStreamEvent.Completed(Response(id = "response", endTurn = true)))
        } }
    }
    val client = object : io.github.stream29.kodex.openai.client.contract.OpenAiClient by mock {
        override fun close() { clientClosed = true; mock.close() }
    }
    try {
        withBackendServices(
            home = root, codexHome = Path(root, "codex"), agentsHome = Path(root, "agents"),
            defaults = defaultBackendSettings().copy(
                newSession = KodexNewSessionSettings(model = OpenAiModelId("test-model")),
                sessionTitle = SessionTitleSettings(titleGenerator != null),
            ),
            createLoginClient = { login }, createClient = { client },
            createTitleGenerator = { titleGenerator ?: OpenAiSessionTitleGenerator(it) },
            checkAdmission = checkAdmission,
        ) { services ->
            block(services, login)
        }
        assertTrue(login.closed)
        assertTrue(clientClosed)
    } finally {
        suspend fun remove(path: Path) {
            val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
            if (metadata.isDirectory) SystemCoroutineFileSystem.list(path).forEach { remove(it) }
            SystemCoroutineFileSystem.delete(path)
        }
        remove(root)
    }
}

internal class ServicesTestLogin : OpenAiLoginClient {
    var exchanges = 0
    var closed = false
    override fun authorizationUrl(request: OpenAiLoginAuthorization): String =
        URLBuilder("https://auth.example.test/authorize").apply {
            parameters.append("state", request.state)
            parameters.append("redirect_uri", request.redirectUri)
        }.buildString()
    override suspend fun exchangeAuthorizationCode(
        request: OpenAiAuthorizationCodeExchange,
    ): OpenAiLoginResult<OpenAiSubscriptionTokens> {
        exchanges++
        return OpenAiResult.Success(
            OpenAiSubscriptionTokens("opaque-id", "access", "refresh", "account"),
        )
    }
    override suspend fun refreshSubscriptionTokens(
        refreshToken: String,
    ): OpenAiLoginResult<OpenAiSubscriptionTokenRefresh> = error("Fresh fixture tokens")
    override fun close() { closed = true }
}
