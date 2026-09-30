package io.github.stream29.kodex.rpc.server

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationState
import io.github.stream29.kodex.app.settings.contract.SettingsAccountUsageState
import io.github.stream29.kodex.cli.settings.KodexAuthSource
import io.github.stream29.kodex.cli.settings.SessionTitleSettings
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
                val index = global.createSession(KodexAgentSettings(OpenAiModelId("test-model")))
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

    test("full-service OAuth persists selected credentials and usage consumes only the selected credit") {
        withServices { services, login ->
            withInMemoryRpc(services::register) { raw ->
                val global = RestoringRpcClient(raw).withService<GlobalRpc>()
                assertIs<SettingsAuthenticationState.Unavailable>(global.getAuthentication())
                val old = global.getSettings()
                assertTrue(global.compareAndSetSettings(old, old.copy(authSource = KodexAuthSource.Kodex)))
                val redirect = "http://localhost:1455/auth/callback"
                val authorization = global.startOAuthLogin(OAuthTarget.OpenAi(KodexAuthSource.Kodex), redirect)
                val callback = URLBuilder(redirect).apply {
                    parameters.append("state", requireNotNull(Url(authorization.url).parameters["state"]))
                    parameters.append("code", "one-code")
                }.buildString()
                global.completeOAuthLogin(authorization.attemptId, callback)
                val summary = global.getAuthenticationFlow().filterIsInstance<SettingsAuthenticationState.Authenticated>().first()
                assertEquals("account", summary.accountId)
                assertEquals(1, login.exchanges)
                global.getAccountUsageFlow().filterIsInstance<SettingsAccountUsageState.Available>().first()
                global.refreshAccountUsage()
                assertEquals(CodexRateLimitResetOutcome.NoCredit, global.consumeUsageReset("specific"))
                global.cancelOAuthLogin(authorization.attemptId)
                global.removeAuthentication(KodexAuthSource.Kodex)
                global.getAuthenticationFlow().filterIsInstance<SettingsAuthenticationState.Unavailable>().first()
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
                val index = global.createSession(KodexAgentSettings(OpenAiModelId("test-model")))
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

private suspend fun withServices(
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
            defaults = defaultBackendSettings().copy(sessionTitle = SessionTitleSettings(false)),
            createLoginClient = { login }, createClient = { client },
        ) { services ->
            services.global.getModelsFlow().first { models -> models.any { it.slug == OpenAiModelId("test-model") } }
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

private class ServicesTestLogin : OpenAiLoginClient {
    var exchanges = 0
    var closed = false
    override fun authorizationUrl(request: OpenAiLoginAuthorization): String =
        URLBuilder("https://auth.example.test/authorize").apply { parameters.append("state", request.state) }.buildString()
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
