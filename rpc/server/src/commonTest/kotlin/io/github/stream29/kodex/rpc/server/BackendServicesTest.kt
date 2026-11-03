package io.github.stream29.kodex.rpc.server

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
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
                val timeline = client.withService<SettingsTimelineRpc>()
                val staleFrontend = global.getSettings()
                val firstCwd = Path("/fixture/旧目录 with spaces")
                val first = global.createSession(firstCwd)
                val firstNonce = timeline.getCacheNonce(first)
                val original = timeline.get(first, firstNonce, 0)
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
                val current = timeline.get(second, timeline.getCacheNonce(second), 0)
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
                assertEquals(original, timeline.get(first, firstNonce, 0))
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
                for (timeline in listOf<TimelineRpc<*>>(
                    client.withService<IndexTimelineRpc>(), client.withService<WorkTimelineRpc>(),
                    client.withService<SettingsTimelineRpc>(), client.withService<TimestampTimelineRpc>(),
                    client.withService<TokenCountTimelineRpc>(), client.withService<UnstableTimelineRpc>(),
                )) {
                    val nonce = timeline.getCacheNonce(index)
                    assertEquals(nonce, timeline.getCacheNonceFlow(index).first())
                    timeline.valuesIn(index, nonce, 0, 1)
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
    val root = Path(SystemTemporaryDirectory, "kodex-rpc-services-${Random.nextLong()}")
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
