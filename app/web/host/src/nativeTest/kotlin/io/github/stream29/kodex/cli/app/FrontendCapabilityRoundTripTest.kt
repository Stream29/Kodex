@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentcontext.contract.ContextPathEvaluation
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationState
import io.github.stream29.kodex.backend.*
import io.github.stream29.kodex.backend.host.runBackendHost
import io.github.stream29.kodex.cli.rpc.RpcServices
import io.github.stream29.kodex.cli.settings.KodexAuthSource
import io.github.stream29.kodex.mcp.contract.*
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.rpc.client.RestoringRpcClient
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import io.github.stream29.kodex.rpc.local.withLocalRpcClient
import io.github.stream29.kodex.rpc.models.*
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.osenvironment.environmentVariable
import io.github.stream29.kodex.utils.shellclient.Shell
import io.github.stream29.kodex.utils.shellclient.ShellType
import io.github.stream29.kodex.utils.shellclient.default
import io.ktor.http.*
import kotlinx.cinterop.convert
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemPathSeparator
import kotlinx.io.writeString
import kotlinx.serialization.json.*
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

// runBackendHost owns process-wide cwd/signals/logging. Run this suite in an isolated test
// executable, not alongside another in-process BackendHost suite or a user's running application.
// The launching controller must supply the private OS-Home environment checked below.
private val roundTripProcess = Mutex()

val frontendCapabilityRoundTripTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("frontend owner never renews an idle backend and closes safely after its expiry") {
        withRoundTrip(ttlSeconds = 5) { f ->
            f.host { page ->
                assertEquals(FrontendCapabilityStatus.NoCompanion, page.info.host.capabilities.frontendErrorObservation)
                withTimeout(15.seconds) {
                    while (BackendInstance.discover(f.home) != null) delay(50)
                }
                assertFalse(frontendBackendReady(f.endpoint))
            }
        }
    }
    test("directory and Context RPC round trips retain launch base and actual private builtins") {
        withRoundTrip { f ->
            f.rpc { rpc ->
                val global = rpc.global
                val query = DirectoryQuery(".", f.launch.toString())
                val selected = assertIs<DirectoryValidationResult.Directory>(global.validateDirectory(query))
                assertEquals(f.launch.toString(), selected.path)
                val listing = assertIs<DirectoryListResult.Directories>(global.listDirectories(query))
                assertEquals(f.launch.toString(), listing.directory)
                assertEquals(f.root.toString(), listing.parentDirectory)
                assertEquals(listOf("Alpha", "beta", "目录 space"), listing.children.map { it.name })
                assertEquals(
                    listing.children.map { Path(f.launch, it.name).toString() },
                    listing.children.map { it.path },
                )
                assertTrue(listing.children.none { it.name == "plain-file" || it.name == "nested" })
                assertEquals(
                    Path(f.launch, "Alpha").toString(),
                    assertIs<DirectoryValidationResult.Directory>(
                        global.validateDirectory(DirectoryQuery("Alpha", f.launch.toString())),
                    ).path,
                )
                assertEquals(
                    DirectoryQueryFailure.NotDirectory,
                    assertIs<DirectoryValidationResult.Failure>(
                        global.validateDirectory(DirectoryQuery("plain-file", f.launch.toString())),
                    ).reason,
                )
                assertEquals(
                    DirectoryQueryFailure.InvalidLaunchBase,
                    assertIs<DirectoryValidationResult.Failure>(
                        global.validateDirectory(DirectoryQuery(".", "relative-base")),
                    ).reason,
                )

                // These are the actual BackendServices roots, not guessed ~/.kodex/.codex paths.
                val originals = listOf(f.home.toString(), "relative", f.codex.toString(), f.agents.toString())
                for (builtin in listOf(f.home, f.codex, f.agents)) {
                    val result = assertIs<ContextPathEvaluation.Evaluated>(
                        global.evaluateContextPaths(ContextPathQuery(
                            builtin.toString(), originals, f.launch.toString(),
                        )),
                    )
                    assertEquals(builtin.toString(), result.candidate.lexicalKey)
                    assertTrue(result.candidate.matchesBuiltIn)
                    assertEquals(4, result.originalPaths.size)
                    assertEquals(listOf(true, false, true, true), result.originalPaths.map { it.matchesBuiltIn })
                    assertNull(result.originalPaths[1].lexicalKey)
                }
                val custom = Path(f.launch, "nonexistent-context").toString()
                val evaluated = assertIs<ContextPathEvaluation.Evaluated>(
                    global.evaluateContextPaths(ContextPathQuery(custom, listOf(custom, "bad\$path"), f.launch.toString())),
                )
                assertEquals(custom, evaluated.candidate.lexicalKey)
                assertFalse(evaluated.candidate.matchesBuiltIn)
                assertEquals(evaluated.candidate, evaluated.originalPaths[0])
                assertNull(evaluated.originalPaths[1].lexicalKey)
                assertFalse(SystemFileSystem.exists(Path(custom)), "Lexical evaluation must not create/scan a root.")
                assertEquals(f.foreignCwd, SystemFileSystem.resolve(Path(".")))
                assertFalse(SystemFileSystem.exists(Path(f.home, "sessions")))
            }
        }
    }

    test("typed host HTTP authenticates exact owner and Origin and rejects retired effects") {
        withRoundTrip { f ->
            f.host { page ->
                val second = f.bootstrap(page.launchUrl)
                val probe = LocalFrontendHostRequest.ReserveCallback(LocalOAuthProvider.Mcp, f.wire.spareRedirect())
                for ((credential, owner) in listOf(
                    f.endpoint.password to page.info.host.owner,
                    page.info.endpoint.password to page.info.host.owner,
                    page.info.host.credential to second.info.host.owner,
                    second.info.host.credential to page.info.host.owner,
                )) {
                    assertEquals(401, page.raw(probe, credential = credential, owner = owner).status)
                }
                assertEquals(403, page.raw(probe, origin = "http://127.0.0.1:1").status)
                assertEquals(403, page.raw(probe, authority = "localhost:${Url(page.info.host.baseUrl).port}").status)
                assertEquals(
                    LocalFrontendHostFailure.CapabilityUnavailable,
                    assertIs<LocalFrontendHostReply.Failure>(page.call(probe)).reason,
                )
                assertEquals(
                    LocalFrontendHostFailure.CapabilityUnavailable,
                    assertIs<LocalFrontendHostReply.Failure>(
                        page.call(LocalFrontendHostRequest.ObserveError("not-admitted")),
                    ).reason,
                )
                assertIs<LocalFrontendHostReply.OwnerClosed>(page.call(LocalFrontendHostRequest.CloseOwner))
                assertEquals(401, page.raw(LocalFrontendHostRequest.RenewOwner).status)
                assertIs<LocalFrontendHostReply.OwnerRenewed>(second.call(LocalFrontendHostRequest.RenewOwner))
                assertIs<LocalFrontendHostReply.OwnerClosed>(second.call(LocalFrontendHostRequest.CloseOwner))
                assertTrue(frontendBackendReady(f.endpoint), "Closing a frontend must not stop the backend.")
            }
        }
    }

    test("expired owner HTTP is rejected without renewing the backend") {
        withRoundTrip { f ->
            f.host { page ->
                val request = LocalFrontendHostRequest.ReserveCallback(LocalOAuthProvider.Mcp, f.wire.spareRedirect())
                assertIs<LocalFrontendHostReply.Failure>(page.call(request))
                // The HTTP composition intentionally has no fake clock; exercise the real finite lease.
                delay(page.info.host.leaseMillis + 100)
                assertEquals(401, page.raw(LocalFrontendHostRequest.RenewOwner).status)
                assertEquals(401, page.raw(request).status)
                assertTrue(frontendBackendReady(f.endpoint))
            }
        }
    }

    for (source in KodexAuthSource.entries) {
        test("OpenAI $source backend callback accepted save survives owner close and RPC cancellation") {
            withRoundTrip { f ->
                f.wire.requireOpenAiPortsFree()
                f.host { page ->
                    f.rpc { rpc ->
                        val old = rpc.global.getSettings()
                        val other = KodexAuthSource.entries.single { it != source }
                        assertTrue(rpc.global.compareAndSetSettings(old, old.copy(authSource = other)))
                        val authorization = rpc.global.startOAuthLogin(OAuthTarget.OpenAi(source))
                        f.authorize(authorization)
                        val waiter = async { rpc.global.awaitOAuthLogin(authorization.attemptId) }
                        try {
                            withTimeout(10.seconds) { f.login.entered.await() }
                            rpc.global.abandonPendingOAuthLogin(authorization.attemptId)
                            assertIs<LocalFrontendHostReply.OwnerClosed>(page.call(LocalFrontendHostRequest.CloseOwner))
                            waiter.cancelAndJoin()
                            assertFalse(f.login.exchangeCancelled.isCompleted)
                            assertFalse(SystemFileSystem.exists(f.authPath(source)))
                        } finally {
                            f.login.release.complete(Unit)
                            waiter.cancelAndJoin()
                        }
                    } // Dispose the real WebSocket too; exchange/persistence are backend-owned.
                    f.rpc { rpc ->
                        f.awaitFile(f.authPath(source))
                        assertEquals(1, f.login.exchanges.value.size)
                        val exchange = f.login.exchanges.value.single()
                        val request = f.login.preparations.value.single()
                        assertEquals(request.redirectUri, exchange.redirectUri)
                        assertEquals("openai-code", exchange.authorizationCode)
                        assertEquals(request.codeChallenge, roundTripPkce(exchange.codeVerifier))
                        val settings = rpc.global.getSettings()
                        assertNotEquals(source, settings.authSource, "Login must not implicitly select its destination.")
                        assertTrue(rpc.global.compareAndSetSettings(settings, settings.copy(authSource = source)))
                        withTimeout(10.seconds) {
                            rpc.global.getAuthenticationFlow().first { it is SettingsAuthenticationState.Authenticated }
                        }
                        val saved = SystemCoroutineFileSystem.readString(f.authPath(source))
                        assertTrue(saved.contains("roundtrip-access"))
                        assertTrue(saved.contains("roundtrip-refresh"))
                        assertFalse(SystemFileSystem.exists(f.authPath(KodexAuthSource.entries.single { it != source })))
                    }
                }
            }
        }
    }

    test("pending abandon and explicit cancel retain terminal results without token exchange") {
        withRoundTrip { f ->
            f.wire.requireOpenAiPortsFree()
            f.host { page ->
                f.rpc { rpc ->
                    val target = OAuthTarget.OpenAi(KodexAuthSource.Kodex)
                    val abandoned = rpc.global.startOAuthLogin(target)
                    rpc.global.abandonPendingOAuthLogin(abandoned.attemptId)
                    rpc.global.abandonPendingOAuthLogin(abandoned.attemptId)
                    val abandonedFailure = assertFails {
                        rpc.global.awaitOAuthLogin(abandoned.attemptId)
                    }
                    assertReleasedAttempt(abandonedFailure)
                    f.wire.assertClosed(requireNotNull(Url(abandoned.url).parameters["redirect_uri"]))
                    val next = rpc.global.startOAuthLogin(target)
                    assertTrue(next.attemptId > abandoned.attemptId)
                    rpc.global.abandonPendingOAuthLogin(abandoned.attemptId)
                    rpc.global.cancelOAuthLogin(next.attemptId)
                    val cancelledFailure = assertFails {
                        rpc.global.awaitOAuthLogin(next.attemptId)
                    }
                    assertReleasedAttempt(cancelledFailure)
                    assertTrue(f.login.exchanges.value.isEmpty())
                    KodexAuthSource.entries.forEach { assertFalse(SystemFileSystem.exists(f.authPath(it))) }
                }
            }
        }
    }

    test("MCP production discovery registration and token exchange persist after accepted owner disposal") {
        withRoundTrip { f ->
            f.host { page ->
                val redirect = f.wire.spareRedirect()
                f.rpc { rpc ->
                    val old = rpc.global.getSettings()
                    val config = McpServerConfiguration.StreamableHttp(
                        url = "${f.provider.origin}/mcp", enabled = false,
                        oauth = McpOAuthConfiguration.Uninitialized(McpOAuthClient(redirectUri = redirect)),
                    )
                    assertTrue(rpc.global.compareAndSetSettings(old, old.copy(mcpServers = mapOf("fixture" to config))))
                    val authorization = rpc.global.startOAuthLogin(OAuthTarget.Mcp("fixture"))
                    val prepared = assertIs<McpOAuthConfiguration.Uninitialized>(
                        (rpc.global.getSettings().mcpServers.getValue("fixture") as McpServerConfiguration.StreamableHttp).oauth,
                    )
                    assertEquals("roundtrip-client", prepared.client.clientId)
                    assertEquals(1, f.provider.registrations.value.size)
                    assertEquals(
                        listOf(redirect),
                        f.provider.registrations.value.single().getValue("redirect_uris").jsonArray.map { it.jsonPrimitive.content },
                    )
                    f.authorize(authorization)
                    val waiter = async { rpc.global.awaitOAuthLogin(authorization.attemptId) }
                    try {
                        withTimeout(10.seconds) { f.provider.tokenEntered.await() }
                        rpc.global.abandonPendingOAuthLogin(authorization.attemptId)
                        assertIs<LocalFrontendHostReply.OwnerClosed>(page.call(LocalFrontendHostRequest.CloseOwner))
                        waiter.cancelAndJoin()
                    } finally {
                        f.provider.releaseToken.complete(Unit)
                        waiter.cancelAndJoin()
                    }
                }
                f.rpc { rpc ->
                    val saved = withTimeout(10.seconds) {
                        rpc.global.getSettingsFlow().map {
                            (it.mcpServers.getValue("fixture") as McpServerConfiguration.StreamableHttp).oauth
                        }.first { it is McpOAuthConfiguration.Initialized }
                    }
                    val initialized = assertIs<McpOAuthConfiguration.Initialized>(saved)
                    assertEquals(McpSecret("roundtrip-mcp-access"), initialized.accessToken)
                    assertEquals(McpSecret("roundtrip-mcp-refresh"), initialized.refreshToken)
                    assertEquals("roundtrip-client", initialized.client.clientId)
                    assertEquals(listOf("tools.read"), initialized.scopes)
                    assertEquals("${f.provider.origin}/mcp", initialized.resource)
                    assertEquals(1, f.provider.tokens.value.size)
                    val token = f.provider.tokens.value.single()
                    val authorization = f.provider.mcpAuthorizations.value.single()
                    assertEquals("authorization_code", token["grant_type"])
                    assertEquals("mcp-code", token["code"])
                    assertEquals(redirect, token["redirect_uri"])
                    assertEquals("roundtrip-client", token["client_id"])
                    assertEquals(authorization.getValue("code_challenge"), roundTripPkce(token.getValue("code_verifier")))
                    assertTrue(SystemCoroutineFileSystem.readString(Path(f.home, "settings.yml")).contains("roundtrip-mcp-access"))
                    // Disabled server: saved authentication is not a fabricated connected SDK session.
                    assertFalse((rpc.global.getSettings().mcpServers.getValue("fixture") as McpServerConfiguration.StreamableHttp).enabled)
                    rpc.global.logoutMcpServer("fixture")
                    val loggedOut = (rpc.global.getSettings().mcpServers.getValue("fixture") as McpServerConfiguration.StreamableHttp).oauth
                    assertIs<McpOAuthConfiguration.Uninitialized>(loggedOut)
                }
            }
        }
    }

    test("admitted frontend Hook survives frontend disposal and runs once in backend Home") {
        withRoundTrip { f ->
            f.host { page ->
                val hooks = listOf(NotificationHook(
                    "roundtrip-only", setOf(NotificationHookType.StopUnhandledError), roundTripMarkerCommand(),
                ))
                val input = Path(f.home, "roundtrip-hook-input")
                val marker = Path(f.home, "roundtrip-hook-count")
                val started = Path(f.home, "roundtrip-hook-started")
                val release = Path(f.home, "roundtrip-hook-release")
                try {
                    f.rpc { rpc ->
                        val before = rpc.global.getFrontendSettings()
                        assertTrue(rpc.global.compareAndSetFrontendSettings(before, before.copy(hooks = hooks)))
                        assertEquals(FrontendErrorAdmission.Admitted, rpc.global.reportFrontendError("roundtrip-error"))
                        f.awaitFile(started)
                        assertFalse(SystemFileSystem.exists(input))
                        assertIs<LocalFrontendHostReply.OwnerClosed>(page.call(LocalFrontendHostRequest.CloseOwner))
                        assertEquals(401, page.raw(LocalFrontendHostRequest.ObserveError("must-not-replay")).status)
                    }
                    // The original frontend's owner and RPC connection are both gone.
                    SystemCoroutineFileSystem.writeString(release, "release")
                    f.awaitFile(input)
                    f.awaitFile(marker)
                    withTimeout(10.seconds) {
                        while (SystemCoroutineFileSystem.readString(marker).trim() != "x") delay(20)
                    }
                    val notification = Json.parseToJsonElement(SystemCoroutineFileSystem.readString(input)).jsonObject
                    assertEquals(setOf("type", "message"), notification.keys)
                    assertEquals("unhandled_error", notification.getValue("type").jsonPrimitive.content)
                    assertEquals("roundtrip-error", notification.getValue("message").jsonPrimitive.content)
                    val replacement = f.bootstrap(page.launchUrl)
                    delay(250)
                    assertIs<LocalFrontendHostReply.OwnerClosed>(replacement.call(LocalFrontendHostRequest.CloseOwner))
                    assertEquals("x", SystemCoroutineFileSystem.readString(marker).trim())
                    for (wrong in listOf(f.launch, f.foreignCwd)) {
                        assertFalse(SystemFileSystem.exists(Path(wrong, "roundtrip-hook-input")))
                        assertFalse(SystemFileSystem.exists(Path(wrong, "roundtrip-hook-count")))
                    }
                    f.rpc { rpc -> assertEquals(hooks, rpc.global.getFrontendSettings().hooks) }
                    assertTrue(frontendBackendReady(f.endpoint))
                } finally {
                    SystemCoroutineFileSystem.writeString(release, "release")
                }
            }
        }
    }
}

private class RoundTripFixture(val root: Path, val wire: RoundTripWire, val provider: RoundTripProvider) {
    val home = Path(root, "private-kodex")
    val codex = Path(root, "private-codex")
    val agents = Path(root, "private-agents")
    val launch = Path(root, "launch-base")
    val foreignCwd = Path(root, "different-process-cwd")
    val login = RoundTripLogin(provider)
    lateinit var endpoint: BackendEndpoint

    suspend fun <R> rpc(block: suspend CoroutineScope.(RpcServices) -> R): R =
        withLocalRpcClient(home, endpoint) { block(RpcServices(RestoringRpcClient(it))) }

    suspend fun <R> host(block: suspend CoroutineScope.(RoundTripPage) -> R): R = withFrontendHostListener(
        endpoint, launch.toString(),
        openResource = { if (it == "index.html") Buffer().apply { writeString("<!doctype html>") } else null },
        newSecret = ::randomBackendPassword,
    ) { launchUrl, _ -> block(bootstrap(launchUrl)) }

    suspend fun bootstrap(launchUrl: String): RoundTripPage {
        val url = Url(launchUrl)
        val response = wire.request(
            launchUrl, "POST", url.encodedPath + "bootstrap",
            origin = "http://127.0.0.1:${url.port}",
            body = LocalFrontendHostJson.encodeToString(LocalWebTicket.serializer(), LocalWebTicket(url.fragment)),
        )
        assertEquals(200, response.status)
        assertFalse(response.body.contains(endpoint.password))
        val info = LocalFrontendHostJson.decodeFromString(LocalFrontendBootstrap.serializer(), response.body)
        assertEquals(endpoint.instanceId, info.endpoint.instanceId)
        assertEquals(launch.toString(), info.workingDirectory)
        assertEquals(launch.toString(), info.host.launchDirectory)
        assertEquals(FrontendCapabilityStatus.NoCompanion, info.host.capabilities.oauthCallbacks)
        assertEquals(FrontendCapabilityStatus.NoCompanion, info.host.capabilities.frontendErrorObservation)
        return RoundTripPage(launchUrl, info, wire)
    }

    suspend fun authorize(authorization: OAuthAuthorization) {
        val uri = Url(authorization.url)
        assertEquals(provider.origin, "${uri.protocol.name}://${uri.host}:${uri.port}")
        val state = requireNotNull(uri.parameters["state"])
        val redirect = requireNotNull(uri.parameters["redirect_uri"])
        assertEquals("S256", uri.parameters["code_challenge_method"])
        assertFalse(uri.parameters["code_challenge"].isNullOrBlank())
        assertTrue(state.isNotBlank())
        val callback = callback(authorization, redirect)
        assertEquals(400, wire.request(callback, origin = null, authority = "untrusted.invalid").status)
        assertEquals(400, wire.request(
            URLBuilder(redirect).apply {
                parameters.append("state", "wrong-state")
                parameters.append("code", "must-not-exchange")
            }.buildString(),
        ).status)
        val callbackPath = Url(redirect).encodedPath
        val alias = wire.request(callback, path = callbackPath.replace("callback", "%63allback"))
        assertTrue(alias.status in listOf(400, 404))
        assertEquals(
            400,
            wire.request(callback, extraHeaders = listOf("Host" to Url(callback).let { "${it.host}:${it.port}" })).status,
        )
        assertEquals(0, provider.visits.value)
        assertTrue(provider.visitAuthorization(wire, authorization.url))
        assertEquals(1, provider.visits.value)
    }

    fun callback(authorization: OAuthAuthorization, redirect: String): String = URLBuilder(redirect).apply {
        parameters.append("state", requireNotNull(Url(authorization.url).parameters["state"]))
        parameters.append("code", "unused-code")
    }.buildString()

    fun authPath(source: KodexAuthSource): Path =
        if (source == KodexAuthSource.Codex) Path(codex, "auth.json") else Path(home, "auth.yml")

    suspend fun awaitFile(path: Path) = withTimeout(10.seconds) {
        while (!SystemFileSystem.exists(path)) delay(20)
    }

}

private fun assertReleasedAttempt(failure: Throwable) {
    assertFalse(failure is CancellationException)
    assertTrue(failure.message in setOf(
        "Authorization was released.",
        "Authorization was cancelled.",
    ), "The exact released attempt must be rejected before any exchange, not fail for an unrelated transport reason.")
}

private class RoundTripPage(
    val launchUrl: String,
    val info: LocalFrontendBootstrap,
    val wire: RoundTripWire,
) {
    suspend fun raw(
        request: LocalFrontendHostRequest,
        credential: String = info.host.credential,
        owner: LocalFrontendOwner = info.host.owner,
        origin: String = info.host.origin,
        authority: String = Url(info.host.baseUrl).let { "${it.host}:${it.port}" },
    ): RoundTripHttpReply = wire.request(
        info.host.baseUrl, "POST", LocalFrontendHostPath, origin, authority,
        LocalFrontendHostJson.encodeToString(LocalFrontendHostCall.serializer(), LocalFrontendHostCall(
            LocalFrontendHostProtocol, info.host.hostInstanceId, credential, owner, request,
        )),
    )

    suspend fun call(request: LocalFrontendHostRequest): LocalFrontendHostReply {
        val response = raw(request)
        assertEquals(200, response.status)
        assertEquals("no-store", response.headers["cache-control"])
        assertFalse(response.body.contains(info.host.credential))
        val result = LocalFrontendHostJson.decodeFromString(LocalFrontendHostResult.serializer(), response.body)
        assertEquals(LocalFrontendHostProtocol, result.protocol)
        assertEquals(info.host.hostInstanceId, result.hostInstanceId)
        assertEquals(info.host.owner.value, result.owner.value)
        return result.reply
    }
}

private class RoundTripLogin(private val provider: RoundTripProvider) : OpenAiLoginClient {
    val preparations = MutableStateFlow<List<OpenAiLoginAuthorization>>(emptyList())
    val exchanges = MutableStateFlow<List<OpenAiAuthorizationCodeExchange>>(emptyList())
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val exchangeCancelled = CompletableDeferred<Unit>()

    override fun authorizationUrl(request: OpenAiLoginAuthorization): String {
        preparations.update { it + request }
        return URLBuilder("${provider.origin}/openai-authorize").apply {
            parameters.append("response_type", "code")
            parameters.append("redirect_uri", request.redirectUri)
            parameters.append("state", request.state)
            parameters.append("code_challenge", request.codeChallenge)
            parameters.append("code_challenge_method", "S256")
        }.buildString()
    }

    override suspend fun exchangeAuthorizationCode(request: OpenAiAuthorizationCodeExchange): OpenAiLoginResult<OpenAiSubscriptionTokens> {
        exchanges.update { it + request }
        assertEquals(preparations.value.single().codeChallenge, roundTripPkce(request.codeVerifier))
        entered.complete(Unit)
        try { release.await() }
        catch (cancelled: CancellationException) { exchangeCancelled.complete(Unit); throw cancelled }
        return OpenAiResult.Success(OpenAiSubscriptionTokens(
            "roundtrip-id", "roundtrip-access", "roundtrip-refresh", "roundtrip-account",
        ))
    }

    override suspend fun refreshSubscriptionTokens(refreshToken: String): OpenAiLoginResult<OpenAiSubscriptionTokenRefresh> =
        error("Fresh task-only fake tokens must not refresh.")
}

private suspend fun withRoundTrip(
    ttlSeconds: Long = -1,
    block: suspend CoroutineScope.(RoundTripFixture) -> Unit,
) =
    roundTripProcess.withLock {
        withTimeout(120.seconds) {
            coroutineScope {
                val originalCwd = SystemFileSystem.resolve(Path("."))
                val sandbox = roundTripSandbox()
                val created = Path(sandbox, "kodex-capability-roundtrip-${Random.nextLong().toULong()}")
                SystemFileSystem.createDirectories(created, mustCreate = true)
                val root = try {
                    check(platform.posix.chmod(created.toString(), 0x1c0.convert()) == 0) { "Cannot protect test Home." }
                    SystemFileSystem.resolve(created)
                } catch (failure: Throwable) {
                    SystemFileSystem.delete(created, mustExist = false)
                    throw failure
                }
                val wire = RoundTripWire()
                val provider = RoundTripProvider()
                val f = RoundTripFixture(root, wire, provider)
                var backend: Job? = null
                try {
                    for (directory in listOf(f.home, f.codex, f.agents, f.launch, f.foreignCwd)) {
                        SystemFileSystem.createDirectories(directory, mustCreate = true)
                    }
                    for (name in listOf("Alpha", "beta", "目录 space")) {
                        SystemFileSystem.createDirectories(Path(f.launch, name), mustCreate = true)
                    }
                    SystemFileSystem.createDirectories(Path(f.launch, "Alpha", "nested"), mustCreate = true)
                    SystemCoroutineFileSystem.writeString(Path(f.launch, "plain-file"), "harmless")
                    SystemCoroutineFileSystem.writeString(Path(f.home, "version.json"), "\"0.4.15\"")
                    SystemCoroutineFileSystem.writeString(Path(f.home, "settings.yml"), """
                        backend:
                          daemon_ttl_seconds: $ttlSeconds
                          session_title:
                            enabled: false
                        frontend: {}
                    """.trimIndent() + "\n")
                    provider.start()
                    val model = mockOpenAiClient {
                        listModels {
                            OpenAiResult.Success(ModelsResponse(listOf(ModelInfo(
                                OpenAiModelId("roundtrip-model"), "Roundtrip fixture",
                                contextWindow = 100_000, maxContextWindow = 100_000,
                            ))))
                        }
                        createResponse { error("No model turn is authorized by this fixture.") }
                    }
                    backend = launch {
                        runBackendHost(
                            f.home, f.codex, f.agents,
                            createClient = { model }, createLoginClient = { f.login },
                        )
                    }
                    withTimeout(20.seconds) {
                        while (true) {
                            val candidate = try { BackendInstance.discover(f.home) }
                            catch (failure: Exception) {
                                currentCoroutineContext().ensureActive()
                                null
                            }
                            if (candidate != null && frontendBackendReady(candidate)) {
                                f.endpoint = candidate
                                break
                            }
                            delay(50)
                        }
                    }
                    setBackendWorkingDirectory(f.foreignCwd)
                    block(f)
                } finally {
                    withContext(NonCancellable) {
                        f.login.release.complete(Unit)
                        provider.releaseToken.complete(Unit)
                        try {
                            // Cancel/join only the in-process backend owned by this fixture.
                            backend?.cancelAndJoin()
                            assertNull(BackendInstance.discover(f.home))
                        } finally {
                            try { provider.close() }
                            finally {
                                try { wire.close() }
                                finally {
                                    setBackendWorkingDirectory(originalCwd)
                                    removeRoundTripTree(root)
                                }
                            }
                        }
                    }
                }
                assertFalse(SystemFileSystem.exists(root))
            }
        }
    }

private fun roundTripMarkerCommand(): String = when (Shell.default.type) {
    ShellType.Sh, ShellType.Bash, ShellType.Zsh ->
        "printf x > roundtrip-hook-started; while [ ! -f roundtrip-hook-release ]; do sleep 0.02; done; " +
            "cat > roundtrip-hook-input; printf x >> roundtrip-hook-count"
    ShellType.PowerShell ->
        "[IO.File]::WriteAllText((Join-Path (Get-Location) 'roundtrip-hook-started'), 'x'); " +
            "while (-not (Test-Path 'roundtrip-hook-release')) { Start-Sleep -Milliseconds 20 }; " +
            "[IO.File]::WriteAllText((Join-Path (Get-Location) 'roundtrip-hook-input'), [Console]::In.ReadToEnd()); " +
            "[IO.File]::AppendAllText((Join-Path (Get-Location) 'roundtrip-hook-count'), 'x')"
    ShellType.Cmd -> error("The stdin JSON marker fixture requires POSIX shell or PowerShell; cmd is not covered.")
}

private fun roundTripSandbox(): Path {
    val sandbox = Path(requireNotNull(environmentVariable("KODEX_FRONTEND_ROUNDTRIP_SANDBOX")) {
        "Launch only this suite in an isolated executable with the documented private OS-Home environment."
    })
    require(sandbox.isAbsolute && sandbox.name.startsWith("kodex-roundtrip-sandbox-"))
    val osHome = Path(sandbox, "os-home").toString()
    require(environmentVariable("HOME") == osHome && environmentVariable("USERPROFILE") == osHome) {
        "Both HOME and USERPROFILE must be the controller's task-only os-home, never the user's Home."
    }
    require(environmentVariable("XDG_CONFIG_HOME") == Path(sandbox, "os-config").toString())
    require(environmentVariable("BASH_ENV").isNullOrEmpty() && environmentVariable("ENV").isNullOrEmpty()) {
        "Shell startup-script environment must be empty."
    }
    if (SystemPathSeparator == '/') {
        require(environmentVariable("SHELL") == "/bin/sh")
        require(environmentVariable("ZDOTDIR") == osHome)
    }
    require(SystemFileSystem.metadataOrNull(sandbox)?.isDirectory == true)
    return sandbox
}

private fun removeRoundTripTree(path: Path) {
    if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
        SystemFileSystem.list(path).forEach(::removeRoundTripTree)
    }
    SystemFileSystem.delete(path, mustExist = false)
}
