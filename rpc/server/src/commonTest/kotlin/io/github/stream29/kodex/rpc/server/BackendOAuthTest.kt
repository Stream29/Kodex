package io.github.stream29.kodex.rpc.server

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentcontext.contract.AgentContextSourceSettings
import io.github.stream29.kodex.cli.auth.BackendFileSystemAuthStore
import io.github.stream29.kodex.cli.settings.KodexAuthSource
import io.github.stream29.kodex.cli.settings.KodexNewSessionSettings
import io.github.stream29.kodex.cli.settings.SessionTitleSettings
import io.github.stream29.kodex.cli.settings.openBackendSettings
import io.github.stream29.kodex.mcp.contract.*
import io.github.stream29.kodex.mcp.impl.PreparedMcpOAuthLogin
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.rpc.models.BackendSettings
import io.github.stream29.kodex.rpc.models.OAuthAuthorization
import io.github.stream29.kodex.rpc.models.OAuthTarget
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.shellclient.Shell
import io.github.stream29.kodex.utils.shellclient.ShellType
import io.github.stream29.kodex.utils.oauthloopback.OAuthCallbackValuePolicy
import io.github.stream29.kodex.utils.oauthloopback.OAuthLoopbackListener
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

val backendOAuthTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("backend-owned callback commits both OpenAI destinations through the existing OAuth engine") {
        for (source in KodexAuthSource.entries) {
            withOAuth { f ->
                val listener = OwnedIntegrationListener(OpenAiRedirect)
                val owned = f.ownedOAuth(this, listener)
                try {
                    val login = owned.start(OAuthTarget.OpenAi(source))
                    assertEquals(OAuthCallbackValuePolicy.RequireNonBlank, listener.policy)
                    val entered = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    f.login.exchange = {
                        entered.complete(Unit)
                        release.await()
                        OpenAiResult.Success(oauthTokens())
                    }
                    listener.deliver(login.callback(OpenAiRedirect))
                    entered.await()
                    owned.abandonPending(login.attemptId)
                    val waiter = async { owned.await(login.attemptId) }
                    release.complete(Unit)
                    waiter.await()
                    owned.await(login.attemptId)
                    assertEquals(1, f.login.exchanged.size)
                    assertEquals(OpenAiRedirect, f.login.exchanged.single().redirectUri)
                    assertTrue(f.authFiles.moves > 0)
                    assertEquals(KodexAuthSource.Codex, f.selected.value)
                } finally { owned.closeAndJoin() }
            }
        }
    }
    test("backend-owned MCP callback saves the prepared server credentials") {
        withOAuth { f ->
            val listener = OwnedIntegrationListener(McpRedirect)
            val owned = f.ownedOAuth(this, listener)
            try {
                val login = owned.start(McpTarget)
                listener.deliver(login.callback(McpRedirect))
                owned.await(login.attemptId)
                val config = f.global.settings.value.mcpServers.getValue("server") as McpServerConfiguration.StreamableHttp
                assertIs<McpOAuthConfiguration.Initialized>(config.oauth)
                assertEquals(1, f.mcpPreparations)
                assertTrue(f.global.mcpService.clients.value.isEmpty())
            } finally { owned.closeAndJoin() }
        }
    }
    test("backend callback admission disarms the original pending deadline before delivery") {
        withOAuth { f ->
            val listener = OwnedIntegrationListener(OpenAiRedirect)
            val owned = f.ownedOAuth(this, listener)
            try {
                val login = owned.start(OpenAiTarget)
                val deadline = f.deadlines.receive()
                val deliver = listener.claim(login.callback(OpenAiRedirect))
                deadline.complete(Unit)
                yield()
                deliver()
                owned.await(login.attemptId)
                assertEquals(1, f.login.exchanged.size)
            } finally { owned.closeAndJoin() }
        }
    }
    test("provider URL state must equal the backend prepared state before it is exposed") {
        withOAuth { f ->
            f.login.stateOverride = "wrong-state"
            val listener = OwnedIntegrationListener(OpenAiRedirect)
            val owned = f.ownedOAuth(this, listener)
            try {
                val failure = assertFailsWith<IllegalStateException> { owned.start(OpenAiTarget) }
                assertFalse(failure.toString().contains("wrong-state"))
                assertNull(listener.policy)
                assertTrue(f.login.exchanged.isEmpty())
            } finally { owned.closeAndJoin() }
        }
    }
    test("OpenAI destination is bound and completion does not select that source") {
        withOAuth { f ->
            val login = f.oauth.start(OAuthTarget.OpenAi(KodexAuthSource.Kodex), OpenAiRedirect)
            f.oauth.complete(login.attemptId, login.callback(OpenAiRedirect))
            assertEquals(KodexAuthSource.Codex, f.selected.value)
            assertIs<OpenAiAuthState.Unavailable>(f.credentials.state.value)
            assertTrue(SystemCoroutineFileSystem.exists(Path(f.root, "auth.yml")))
            assertFalse(SystemCoroutineFileSystem.exists(Path(f.root, "codex", "auth.json")))
            f.selected.value = KodexAuthSource.Kodex
            f.credentials.state.first { it is OpenAiAuthState.Authenticated }
            assertEquals(OpenAiRedirect, f.login.exchanged.single().redirectUri)
        }
    }
    test("mismatched and malformed callbacks do not consume the pending attempt") {
        withOAuth { f ->
            val login = f.oauth.start(OpenAiTarget, OpenAiRedirect)
            val wrong = listOf(
                login.callback("https://attacker.example.test/auth/callback"),
                "$OpenAiRedirect?state=wrong&code=code",
                login.callback(OpenAiRedirect) + "&state=another",
                login.callback(OpenAiRedirect) + "&code=another",
                login.callback(OpenAiRedirect) + "&error=denied",
                login.callback(OpenAiRedirect) + "#fragment",
            )
            wrong.forEach { assertFailsWith<IllegalArgumentException> { f.oauth.complete(login.attemptId, it) } }
            assertTrue(f.login.exchanged.isEmpty())
            f.oauth.complete(login.attemptId, login.callback(OpenAiRedirect))
            assertEquals(1, f.login.exchanged.size)
            assertFailsWith<IllegalStateException> { f.oauth.complete(login.attemptId, login.callback(OpenAiRedirect)) }
        }
    }
    test("a matching OAuth error terminates without exchange and permits a new attempt after cleanup") {
        withOAuth { f ->
            val login = f.oauth.start(OpenAiTarget, OpenAiRedirect)
            val error = URLBuilder(OpenAiRedirect).apply {
                parameters.append("state", requireNotNull(Url(login.url).parameters["state"]))
                parameters.append("error", "access_denied")
            }.buildString()
            assertFailsWith<IllegalStateException> { f.oauth.complete(login.attemptId, error) }
            assertTrue(f.login.exchanged.isEmpty())
            val next = f.startAfterCleanup(OpenAiTarget, OpenAiRedirect)
            assertTrue(next.attemptId > login.attemptId)
            f.oauth.cancel(next.attemptId)
        }
    }
    test("accepted exchange survives cancellation of its RPC waiter and commits once") {
        withOAuth { f ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.login.exchange = {
                entered.complete(Unit)
                release.await()
                OpenAiResult.Success(oauthTokens())
            }
            val login = f.oauth.start(OpenAiTarget, OpenAiRedirect)
            val waiter = async { f.oauth.complete(login.attemptId, login.callback(OpenAiRedirect)) }
            entered.await()
            assertFailsWith<IllegalStateException> { f.oauth.complete(login.attemptId, login.callback(OpenAiRedirect)) }
            waiter.cancelAndJoin()
            release.complete(Unit)
            f.credentials.state.first { it is OpenAiAuthState.Authenticated }
            assertEquals(1, f.login.exchanged.size)
        }
    }
    test("pending deadline is ten minutes and releases an undelivered authorization handle") {
        withOAuth { f ->
            val login = f.oauth.start(OpenAiTarget, OpenAiRedirect)
            f.deadlines.receive().complete(Unit)
            val next = f.startAfterCleanup(OpenAiTarget, OpenAiRedirect)
            assertTrue(next.attemptId > login.attemptId)
            assertFailsWith<IllegalStateException> { f.oauth.complete(login.attemptId, login.callback(OpenAiRedirect)) }
            f.oauth.complete(next.attemptId, next.callback(OpenAiRedirect))
        }
    }
    test("waiting deadline never cancels an accepted exchange") {
        withOAuth { f ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.login.exchange = {
                entered.complete(Unit)
                release.await()
                OpenAiResult.Success(oauthTokens())
            }
            val login = f.oauth.start(OpenAiTarget, OpenAiRedirect)
            val deadline = f.deadlines.receive()
            val waiter = async { f.oauth.complete(login.attemptId, login.callback(OpenAiRedirect)) }
            entered.await()
            deadline.complete(Unit)
            release.complete(Unit)
            waiter.await()
            assertIs<OpenAiAuthState.Authenticated>(f.credentials.state.value)
        }
    }
    test("exact cancellation is idempotent and cannot cancel a newer attempt") {
        withOAuth { f ->
            val old = f.oauth.start(OpenAiTarget, OpenAiRedirect)
            f.oauth.cancel(old.attemptId)
            val next = f.startAfterCleanup(OpenAiTarget, OpenAiRedirect)
            f.oauth.cancel(old.attemptId)
            f.oauth.cancel(Long.MAX_VALUE)
            f.oauth.complete(next.attemptId, next.callback(OpenAiRedirect))
            assertEquals(1, f.login.exchanged.size)
        }
    }
    test("abandon pending is exact idempotent and rejects the abandoned callback without affecting its successor") {
        withOAuth { f ->
            val old = f.oauth.start(OpenAiTarget, OpenAiRedirect)
            f.oauth.abandonPending(old.attemptId)
            val next = f.startAfterCleanup(OpenAiTarget, OpenAiRedirect)
            f.oauth.abandonPending(old.attemptId)
            f.oauth.abandonPending(Long.MAX_VALUE)
            assertFailsWith<IllegalStateException> { f.oauth.complete(old.attemptId, old.callback(OpenAiRedirect)) }
            assertTrue(f.login.exchanged.isEmpty())
            f.oauth.complete(next.attemptId, next.callback(OpenAiRedirect))
            f.oauth.abandonPending(next.attemptId)
            assertEquals(1, f.login.exchanged.size)
        }
    }
    test("abandon during noncooperative preparation cannot publish ready or resurrect the old attempt") {
        withOAuth { f ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.mcpPrepareGate = {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
            }
            val waiter = async { runCatching { f.oauth.start(McpTarget, McpRedirect) } }
            entered.await()
            // Isolated fixture's first id, deliberately known only by this registry test.
            // Production never guesses an id when a start reply has been lost.
            f.oauth.abandonPending(1L)
            release.complete(Unit)
            assertTrue(waiter.await().isFailure)
            f.mcpPrepareGate = {}
            val next = f.startAfterCleanup(McpTarget, McpRedirect)
            assertTrue(next.attemptId > 1L)
            f.oauth.complete(next.attemptId, next.callback(McpRedirect))
            assertIs<McpOAuthConfiguration.Initialized>(
                (f.global.settings.value.mcpServers.getValue("server") as McpServerConfiguration.StreamableHttp).oauth)
        }
    }
    test("pending-only release preserves an accepted OpenAI exchange while explicit cancel still terminates it") {
        withOAuth { f ->
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            f.login.exchange = {
                try { entered.complete(Unit); awaitCancellation() }
                finally { cancelled.complete(Unit) }
            }
            val login = f.oauth.start(OpenAiTarget, OpenAiRedirect)
            val waiter = async { runCatching { f.oauth.complete(login.attemptId, login.callback(OpenAiRedirect)) } }
            entered.await()
            f.oauth.abandonPending(login.attemptId)
            assertFalse(cancelled.isCompleted)
            f.oauth.cancel(login.attemptId)
            cancelled.await()
            assertTrue(waiter.await().isFailure)
            assertFalse(SystemCoroutineFileSystem.exists(Path(f.root, "codex", "auth.json")))
        }
    }
    for (source in KodexAuthSource.entries) {
        test("pending-only release cannot cancel accepted credential persistence for $source") {
            withOAuth { f ->
                f.selected.value = source
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                f.authFiles.beforeMove = { entered.complete(Unit); release.await() }
                val login = f.oauth.start(OAuthTarget.OpenAi(source), OpenAiRedirect)
                val waiter = async { f.oauth.complete(login.attemptId, login.callback(OpenAiRedirect)) }
                entered.await() // Token exchange finished; atomic credential replacement is gated.
                f.oauth.abandonPending(login.attemptId)
                waiter.cancelAndJoin()
                val path = if (source == KodexAuthSource.Codex) Path(f.root, "codex", "auth.json")
                    else Path(f.root, "auth.yml")
                assertFalse(SystemCoroutineFileSystem.exists(path))
                release.complete(Unit)
                f.credentials.state.first { it is OpenAiAuthState.Authenticated }
                assertTrue(SystemCoroutineFileSystem.exists(path))
                assertEquals(1, f.authFiles.moves)
                assertEquals(1, f.login.exchanged.size)
            }
        }
    }
    test("pending-only release preserves accepted MCP exchange and saves once without inventing health") {
        withOAuth { f ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var exchanges = 0
            f.mcpExchangeGate = { exchanges++; entered.complete(Unit); release.await() }
            val login = f.oauth.start(McpTarget, McpRedirect)
            val waiter = async { f.oauth.complete(login.attemptId, login.callback(McpRedirect)) }
            entered.await()
            f.oauth.abandonPending(login.attemptId)
            waiter.cancelAndJoin()
            release.complete(Unit)
            f.global.settings.first {
                (it.mcpServers.getValue("server") as McpServerConfiguration.StreamableHttp)
                    .oauth is McpOAuthConfiguration.Initialized
            }
            assertEquals(1, exchanges)
            assertTrue(f.global.mcpService.clients.value.isEmpty())
            assertFailsWith<IllegalStateException> { f.oauth.complete(login.attemptId, login.callback(McpRedirect)) }
        }
    }
    test("remove invalidates a pending login without changing the other credential source") {
        withOAuth { f ->
            val generation = f.credentials.beginLogin(KodexAuthSource.Kodex)
            f.credentials.commitLogin(KodexAuthSource.Kodex, generation, oauthTokens())
            val pending = f.oauth.start(OpenAiTarget, OpenAiRedirect)
            f.oauth.removeAuthentication(KodexAuthSource.Codex)
            assertFails { f.oauth.complete(pending.attemptId, pending.callback(OpenAiRedirect)) }
            assertTrue(SystemCoroutineFileSystem.exists(Path(f.root, "auth.yml")))
            assertFalse(SystemCoroutineFileSystem.exists(Path(f.root, "codex", "auth.json")))
        }
    }
    test("MCP redirect is validated before discovery or registration") {
        withOAuth { f ->
            assertFailsWith<IllegalArgumentException> { f.oauth.start(McpTarget, "http://localhost:8766/callback") }
            assertEquals(0, f.mcpPreparations)
            val login = f.startAfterCleanup(McpTarget, McpRedirect)
            assertEquals(1, f.mcpPreparations)
            f.oauth.cancel(login.attemptId)
        }
    }
    test("a cancelled start waiter cannot strand a prepared MCP attempt beyond its deadline") {
        withOAuth { f ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.mcpPrepareGate = { entered.complete(Unit); release.await() }
            val waiter = async { f.oauth.start(McpTarget, McpRedirect) }
            entered.await()
            waiter.cancelAndJoin()
            release.complete(Unit)
            f.deadlines.receive().complete(Unit)
            f.mcpPrepareGate = {}
            val next = f.startAfterCleanup(McpTarget, McpRedirect)
            assertTrue(next.attemptId > 1)
            f.oauth.cancel(next.attemptId)
        }
    }
    test("MCP preparation persists registration and completion saves credentials not fake health") {
        withOAuth { f ->
            val login = f.oauth.start(McpTarget, McpRedirect)
            val prepared = (f.global.settings.value.mcpServers.getValue("server") as McpServerConfiguration.StreamableHttp).oauth
            assertEquals("registered", assertIs<McpOAuthConfiguration.Uninitialized>(prepared).client.clientId)
            assertFailsWith<IllegalArgumentException> { f.oauth.logoutMcpServer("server") }
            f.oauth.complete(login.attemptId, login.callback(McpRedirect))
            val saved = (f.global.settings.value.mcpServers.getValue("server") as McpServerConfiguration.StreamableHttp).oauth
            assertIs<McpOAuthConfiguration.Initialized>(saved)
            assertTrue(f.global.mcpService.clients.value.isEmpty())
            f.oauth.logoutMcpServer("server")
            assertIs<McpOAuthConfiguration.Uninitialized>(
                (f.global.settings.value.mcpServers.getValue("server") as McpServerConfiguration.StreamableHttp).oauth)
        }
    }
    test("MCP changed server URL rejects the old callback commit") {
        withOAuth { f ->
            val login = f.oauth.start(McpTarget, McpRedirect)
            val current = f.global.settings.value
            val config = current.mcpServers.getValue("server") as McpServerConfiguration.StreamableHttp
            assertTrue(f.global.compareAndSetSettings(current, current.copy(
                mcpServers = mapOf("server" to config.copy(url = "https://changed.example.test/mcp")),
            )))
            assertFailsWith<IllegalArgumentException> { f.oauth.complete(login.attemptId, login.callback(McpRedirect)) }
            assertIs<McpOAuthConfiguration.Uninitialized>(
                (f.global.settings.value.mcpServers.getValue("server") as McpServerConfiguration.StreamableHttp).oauth)
        }
    }
    test("deleting an MCP server cancels its exchange and cannot restore it after recreation") {
        withOAuth { f ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.mcpExchangeGate = {
                entered.complete(Unit)
                // Simulate a provider whose request finishes despite cancellation.
                withContext(NonCancellable) { release.await() }
            }
            val login = f.oauth.start(McpTarget, McpRedirect)
            val waiter = async { runCatching { f.oauth.complete(login.attemptId, login.callback(McpRedirect)) } }
            entered.await()
            val original = f.global.settings.value
            val empty = original.copy(mcpServers = emptyMap())
            assertTrue(f.global.compareAndSetSettings(original, empty))
            assertTrue(f.global.compareAndSetSettings(empty, original))
            release.complete(Unit)
            assertTrue(waiter.await().isFailure)
            assertIs<McpOAuthConfiguration.Uninitialized>(
                (f.global.settings.value.mcpServers.getValue("server") as McpServerConfiguration.StreamableHttp).oauth)
        }
    }
    test("backend shutdown joins exchange cleanup and releases login resources") {
        val cleaned = CompletableDeferred<Unit>()
        withOAuth { f ->
            val entered = CompletableDeferred<Unit>()
            f.login.exchange = {
                try { entered.complete(Unit); awaitCancellation() }
                finally { cleaned.complete(Unit) }
            }
            val login = f.oauth.start(OpenAiTarget, OpenAiRedirect)
            val waiter = async { runCatching { f.oauth.complete(login.attemptId, login.callback(OpenAiRedirect)) } }
            entered.await()
            f.oauth.close()
            assertTrue(waiter.await().isFailure)
        }
        assertTrue(cleaned.isCompleted)
    }
}

private suspend fun withOAuth(block: suspend CoroutineScope.(OAuthFixture) -> Unit) = coroutineScope {
    val root = Path(SystemTemporaryDirectory, "kodex-rpc-oauth-${Random.nextLong()}")
    val login = OAuthTestLoginClient()
    val selected = MutableStateFlow(KodexAuthSource.Codex)
    val deadlines = Channel<CompletableDeferred<Unit>>(Channel.UNLIMITED)
    val authFiles = OAuthTestFileSystem()
    try {
        coroutineScope {
            val credentials = BackendFileSystemAuthStore(root, Path(root, "codex"), selected, login, authFiles)
            try {
                val unified = io.github.stream29.kodex.cli.settings.UnifiedSettingsStore.open(root, BackendSettings(
                    KodexAuthSource.Codex, Shell(ShellType.Sh, Path("/bin/sh")), AgentContextSourceSettings(),
                    KodexNewSessionSettings(), SessionTitleSettings(),
                    mapOf("server" to McpServerConfiguration.StreamableHttp(
                        "https://mcp.example.test", oauth = McpOAuthConfiguration.Uninitialized(
                            McpOAuthClient(redirectUri = McpRedirect)), enabled = false,
                    )),
                ))
                val settings = unified.backendStore
                lateinit var fixture: OAuthFixture
                val bridge = BackendMcpOAuthBridge { config ->
                    fixture.mcpPreparations++
                    fixture.mcpPrepareGate()
                    val oauth = config.oauth as McpOAuthConfiguration.Uninitialized
                    val prepared = oauth.copy(client = oauth.client.copy(clientId = "registered"))
                    val authorizationUrl = URLBuilder("https://auth.example.test").apply {
                        parameters.append("state", "mcp-state")
                        parameters.append("redirect_uri", prepared.client.redirectUri)
                    }.buildString()
                    PreparedMcpOAuthLogin(prepared, "mcp-state", authorizationUrl) {
                        fixture.mcpExchangeGate()
                        McpOAuthConfiguration.Initialized(
                            prepared.client, resolvedAuthorizationEndpoint = "https://auth.example.test",
                            resolvedTokenEndpoint = "https://token.example.test", accessToken = McpSecret("mcp-token"),
                        )
                    }
                }
                withBackendGlobalState(
                    settings, unified, root, root, Path(root, "codex"),
                    mockOpenAiClient { listModels { awaitCancellation() } }, credentials, bridge,
                    serviceFactory = { _, _ -> OAuthTestMcpService() },
                ) { global ->
                    val oauth = BackendOAuth(this, credentials, login, global.mcpManager) { duration ->
                        assertEquals(10.minutes, duration)
                        val gate = CompletableDeferred<Unit>()
                        deadlines.send(gate)
                        gate.await()
                    }
                    fixture = OAuthFixture(root, oauth, credentials, global, selected, login, deadlines, authFiles)
                    try { block(fixture) } finally { oauth.close() }
                }
            } finally { credentials.close() }
        }
    } finally {
        deadlines.close()
        removeOAuthTree(root)
    }
}

private class OAuthFixture(
    val root: Path,
    val oauth: BackendOAuth,
    val credentials: BackendFileSystemAuthStore,
    val global: BackendGlobalState,
    val selected: MutableStateFlow<KodexAuthSource>,
    val login: OAuthTestLoginClient,
    val deadlines: Channel<CompletableDeferred<Unit>>,
    val authFiles: OAuthTestFileSystem,
) {
    var mcpPreparations = 0
    var mcpPrepareGate: suspend () -> Unit = {}
    var mcpExchangeGate: suspend () -> Unit = {}
    fun ownedOAuth(scope: CoroutineScope, listener: OwnedIntegrationListener): BackendOwnedOAuth = BackendOwnedOAuth(
        scope = scope,
        candidates = { listOf(listener.redirectUri) },
        begin = oauth::begin,
        waitingState = oauth::waitingState,
        holdPendingDeadline = oauth::holdPendingDeadline,
        complete = oauth::complete,
        cancelInner = oauth::cancel,
        releaseInner = oauth::abandonPending,
        openListener = { _, _ -> listener },
    )
    suspend fun startAfterCleanup(target: OAuthTarget, redirect: String): OAuthAuthorization = withTimeout(5.seconds) {
        while (true) {
            try { return@withTimeout oauth.start(target, redirect) }
            catch (notCleaned: IllegalArgumentException) {
                if (notCleaned.message != "This target is already authorizing.") throw notCleaned
                yield()
            }
        }
        error("unreachable")
    }
}

private class OAuthTestFileSystem : CoroutineFileSystem by SystemCoroutineFileSystem {
    var beforeMove: suspend () -> Unit = {}
    var moves = 0
    override suspend fun atomicMove(source: Path, destination: Path) {
        beforeMove()
        SystemCoroutineFileSystem.atomicMove(source, destination)
        moves++
    }
}

private class OAuthTestLoginClient : OpenAiLoginClient {
    val exchanged = mutableListOf<OpenAiAuthorizationCodeExchange>()
    var stateOverride: String? = null
    var exchange: suspend () -> OpenAiLoginResult<OpenAiSubscriptionTokens> = {
        OpenAiResult.Success(oauthTokens())
    }
    override fun authorizationUrl(request: OpenAiLoginAuthorization): String =
        URLBuilder("https://auth.example.test/authorize").apply {
            parameters.append("state", stateOverride ?: request.state)
            parameters.append("redirect_uri", request.redirectUri)
        }.buildString()
    override suspend fun exchangeAuthorizationCode(
        request: OpenAiAuthorizationCodeExchange,
    ): OpenAiLoginResult<OpenAiSubscriptionTokens> {
        exchanged += request
        return exchange()
    }
    override suspend fun refreshSubscriptionTokens(
        refreshToken: String,
    ): OpenAiLoginResult<OpenAiSubscriptionTokenRefresh> =
        error("Fresh fake credentials must not refresh.")
}

private class OwnedIntegrationListener(override val redirectUri: String) : OAuthLoopbackListener {
    val callback = CompletableDeferred<String>()
    var policy: OAuthCallbackValuePolicy? = null
    private var admit: suspend () -> Boolean = { true }

    override suspend fun armState(state: String, valuePolicy: OAuthCallbackValuePolicy) =
        armStateWithAdmission(state, valuePolicy) { true }

    override suspend fun armStateWithAdmission(
        state: String, valuePolicy: OAuthCallbackValuePolicy, admit: suspend () -> Boolean,
    ) {
        require(state.isNotBlank())
        policy = valuePolicy
        this.admit = admit
    }

    suspend fun claim(value: String): () -> Unit { check(admit()); return { callback.complete(value) } }
    suspend fun deliver(value: String) { claim(value)() }
    override suspend fun awaitCallback(): String = callback.await()

    override suspend fun closeAndJoin() {
        callback.cancel()
    }
}
private class OAuthTestMcpService : McpService {
    override val clients = MutableStateFlow<Map<String, McpClient>>(emptyMap())
    override val authentication = MutableStateFlow<Map<String, McpAuthenticationState>>(emptyMap())
    override suspend fun invalidate(serverName: String): Unit = error("No Replace command.")
    override suspend fun refresh() = Unit
    override fun close() = Unit
}
private fun OAuthAuthorization.callback(redirect: String): String = URLBuilder(redirect).apply {
    parameters.append("state", requireNotNull(Url(url).parameters["state"]))
    parameters.append("code", "one-code")
}.buildString()
private fun oauthTokens() = OpenAiSubscriptionTokens("opaque-id", "access", "refresh", "account")
private const val OpenAiRedirect = "http://localhost:1455/auth/callback"
private const val McpRedirect = "http://localhost:8765/callback"
private val OpenAiTarget = OAuthTarget.OpenAi(KodexAuthSource.Codex)
private val McpTarget = OAuthTarget.Mcp("server")
private suspend fun removeOAuthTree(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) SystemCoroutineFileSystem.list(path).forEach { removeOAuthTree(it) }
    SystemCoroutineFileSystem.delete(path)
}
