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
import io.github.stream29.kodex.rpc.client.RpcWireSerializers
import io.github.stream29.kodex.rpc.control.BackendControlClient
import io.github.stream29.kodex.rpc.local.withLocalRpcClient
import io.github.stream29.kodex.rpc.models.*
import io.github.stream29.kodex.rpc.websocket.WebSocketRpcTransport
import io.github.stream29.kodex.utils.rpcexception.BackendLocalCredentialRequired
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.osenvironment.environmentVariable
import io.github.stream29.kodex.utils.processclient.ProcessClient
import io.github.stream29.kodex.utils.processclient.ProcessCommand
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineRawSource
import io.github.stream29.kodex.utils.shellclient.Shell
import io.github.stream29.kodex.utils.shellclient.ShellType
import io.github.stream29.kodex.utils.shellclient.default
import io.github.stream29.kodex.utils.shellclient.renderedBytes
import io.ktor.http.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.rpc.krpc.client.InitializedKrpcClient
import kotlinx.rpc.krpc.rpcClientConfig
import kotlinx.rpc.krpc.serialization.json.json
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
import kotlinx.io.readString
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.serialization.json.*
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// runBackendHost owns process-wide cwd/signals/logging. Run this suite in an isolated test
// executable, not alongside another in-process BackendHost suite or a user's running application.
// The launching controller must supply the private OS-Home environment checked below.
private val roundTripProcess = Mutex()

val frontendCapabilityRoundTripTest by testSuite(compartment = { TestCompartment.RealTime }) {
    val candidate = environmentVariable("KODEX_SESSION_CLI_TEST_BINARY")?.let { Path(it) }
    if (candidate != null) {
    test("actual session CLI accepts work, preserves nested inheritance, and controls the running Agent") {
            runSessionCliProcessRoundTrip(candidate)
        }
    }
    test("Agent SQL accepts local credentials but admitted browser grants see neither method") {
        withRoundTrip { fixture ->
            fixture.rpc { native ->
                assertEquals(AgentSqlQueryModelVersion, native.global.getAgentSqlSchema().queryModelVersion)
                assertNull(native.global.queryAgentSql("SELECT count(*) FROM sessions").error)
            }
            fixture.host { page ->
                page.browserRpc { browser ->
                    assertTrue(browser.global.getSessionCatalog(true).isEmpty())
                    assertFailsWith<BackendLocalCredentialRequired> { browser.global.getAgentSqlSchema() }
                    assertFailsWith<BackendLocalCredentialRequired> { browser.global.queryAgentSql("SELECT 1") }
                    assertTrue(browser.global.getSessionCatalog(true).isEmpty())
                }
                assertEquals(200, page.heartbeat().status)
            }
        }
    }

    test("two live terminals with the same intent stay distinct when reading and closing by registration") {
        val markers = listOf("INTENT_TWIN_ONE", "INTENT_TWIN_TWO")
        withRoundTrip(response = { request ->
            if (request.input.any { it is ResponseItem.FunctionCallOutput && it.callId.startsWith("intent-twin-") }) {
                flowOf(ResponsesStreamEvent.Completed(Response(id = "intent-twins-end", endTurn = true)))
            } else {
                val commands = markers.mapIndexed { index, marker ->
                    ResponsesStreamEvent.OutputItemDone(index.toLong(), ResponseItem.FunctionCall(
                        name = "exec_command", callId = "intent-twin-$index", arguments = buildJsonObject {
                            put("cmd", "printf '%s\\n' $marker; IFS= read -r line")
                            put("name", "Read twin output")
                            put("yield_time_ms", 250)
                        }.toString(),
                    ))
                }
                (commands + ResponsesStreamEvent.Completed(Response(id = "intent-twins-exec", endTurn = false))).asFlow()
            }
        }) { fixture ->
            fixture.rpc { rpc ->
                val agent = rpc.global.createSession(fixture.launch)
                rpc.runtime.submit(agent, listOf(ContentItem.InputText("Run the private twin fixture.")))
                val registry = withTimeout(12.seconds) {
                    rpc.runtime.getShellSessionsFlow(agent).first { it.size == 2 && it.values.none(ShellSessionState::completed) }
                }
                assertEquals(setOf("Read twin output"), registry.values.map { it.arguments.name }.toSet())
                assertEquals(2, registry.values.map(ShellSessionState::registrationNonce).toSet().size)
                val entries = markers.map { marker -> registry.entries.single { marker in it.value.arguments.command } }
                suspend fun output(entry: Map.Entry<Int, ShellSessionState>) = rpc.runtime.readShellSessionOutput(
                    agent, entry.key, expectedRegistrationNonce = entry.value.registrationNonce,
                ).renderedBytes().decodeToString()
                withTimeout(6.seconds) {
                    for ((index, entry) in entries.withIndex()) {
                        var text = output(entry)
                        while (markers[index] !in text) { delay(25); text = output(entry) }
                        assertFalse(markers[1 - index] in text)
                    }
                }
                val (first, second) = entries
                rpc.runtime.closeShellSession(agent, first.key, first.value.registrationNonce)
                val after = withTimeout(6.seconds) {
                    rpc.runtime.getShellSessionsFlow(agent).first { it[first.key]?.completed != false }
                }
                assertEquals(second.value, after[second.key])
                assertTrue(markers[1] in output(second))
                rpc.runtime.closeShellSession(agent, second.key, second.value.registrationNonce)
            }
        }
    }
    test("hosting an unused grant never renews an idle backend") {
        withRoundTrip(ttlSeconds = 5) { f ->
            f.host { page ->
                assertEquals(LocalFrontendBootstrapProtocol, page.info.protocol)
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

    test("reload shares one grant and different hosts revoke only their own admission") {
        withRoundTrip { f ->
            f.host { page ->
                val second = f.bootstrap(page.launchUrl)
                assertEquals(page.info.endpoint.password, second.info.endpoint.password)
                assertEquals(401, page.heartbeat(credential = f.endpoint.password).status)
                assertEquals(403, page.heartbeat(origin = "http://127.0.0.1:1").status)
                assertEquals(200, second.heartbeat().status)
                lateinit var nested: RoundTripPage
                f.host { independent ->
                    nested = independent
                    assertNotEquals(page.info.endpoint.password, independent.info.endpoint.password)
                    assertEquals(401, independent.heartbeat(credential = page.info.endpoint.password).status)
                    assertEquals(200, independent.heartbeat().status)
                }
                assertEquals(403, nested.heartbeat().status)
                assertEquals(200, page.heartbeat().status)
                assertTrue(frontendBackendReady(f.endpoint), "Closing a frontend must not stop the backend.")
            }
        }
    }

    test("a frozen page grant expires and a later bootstrap cannot revive it") {
        withRoundTrip { f ->
            f.host { page ->
                delay(60.seconds + 200.milliseconds)
                assertEquals(403, page.heartbeat().status)
                val stale = f.bootstrap(page.launchUrl)
                assertEquals(page.info.endpoint.password, stale.info.endpoint.password)
                assertEquals(403, stale.heartbeat().status)
                assertTrue(frontendBackendReady(f.endpoint))
            }
        }
    }

    for (source in KodexAuthSource.entries) {
        test("OpenAI $source backend callback accepted save survives frontend RPC cancellation") {
            withRoundTrip { f ->
                f.wire.requireOpenAiPortsFree()
                f.host { _ ->
                    f.rpc(bound = true) { rpc ->
                        val old = rpc.global.getSettings()
                        val other = KodexAuthSource.entries.single { it != source }
                        assertTrue(rpc.global.compareAndSetSettings(old, old.copy(authSource = other)))
                        val (authorization, waiter) = observeRoundTripOAuth(rpc.global.startOAuthLogin(OAuthTarget.OpenAi(source)))
                        f.authorize(authorization)
                        try {
                            withTimeout(10.seconds) { f.login.entered.await() }
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

    test("dropping pending observation and exact cancellation release listeners without exchange") {
        withRoundTrip { f ->
            f.wire.requireOpenAiPortsFree()
            f.host { _ ->
                f.rpc(bound = true) { rpc ->
                    val target = OAuthTarget.OpenAi(KodexAuthSource.Kodex)
                    val (abandoned, observer) = observeRoundTripOAuth(rpc.global.startOAuthLogin(target))
                    observer.cancelAndJoin()
                    f.wire.assertClosed(requireNotNull(Url(abandoned.url).parameters["redirect_uri"]))
                    val (next, nextObserver) = observeRoundTripOAuth(rpc.global.startOAuthLogin(target))
                    assertTrue(next.attemptId > abandoned.attemptId)
                    rpc.global.cancelOAuthLogin(abandoned.attemptId)
                    rpc.global.cancelOAuthLogin(next.attemptId)
                    val cancelledFailure = assertFails {
                        nextObserver.await().getOrThrow()
                    }
                    assertReleasedAttempt(cancelledFailure)
                    assertTrue(f.login.exchanges.value.isEmpty())
                    KodexAuthSource.entries.forEach { assertFalse(SystemFileSystem.exists(f.authPath(it))) }
                }
            }
        }
    }

    test("MCP production discovery registration and token exchange persist after frontend disposal") {
        withRoundTrip { f ->
            f.host { _ ->
                val redirect = f.wire.spareRedirect()
                f.rpc(bound = true) { rpc ->
                    val old = rpc.global.getSettings()
                    val config = McpServerConfiguration.StreamableHttp(
                        url = "${f.provider.origin}/mcp", enabled = false,
                        oauth = McpOAuthConfiguration.Uninitialized(McpOAuthClient(redirectUri = redirect)),
                    )
                    assertTrue(rpc.global.compareAndSetSettings(old, old.copy(mcpServers = mapOf("fixture" to config))))
                    val (authorization, waiter) = observeRoundTripOAuth(rpc.global.startOAuthLogin(OAuthTarget.Mcp("fixture")))
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
                    try {
                        withTimeout(10.seconds) { f.provider.tokenEntered.await() }
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
                    }
                    // The original business connection is gone.
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
                    assertEquals(200, replacement.heartbeat().status)
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

@OptIn(ExperimentalAtomicApi::class)
private suspend fun runSessionCliProcessRoundTrip(binary: Path) {
    require(binary.isAbsolute && SystemFileSystem.metadataOrNull(binary)?.isRegularFile == true)
    val calls = AtomicInt(0)
    val childEntered = CompletableDeferred<Unit>()
    val resumeEntered = CompletableDeferred<Unit>()
    val shellBinary = "'" + binary.toString().replace("'", "'\\''") + "'"
    val nestedCommand = """cd Alpha && /bin/sh -c 'exec "${'$'}1" session spawn' sh $shellBinary > ../nested-spawn.json"""
    withRoundTrip(compact = { RemoteCompactionV2Response(ResponseItem.Compaction(encryptedContent = "fixture"), null) }, response = {
        when (calls.fetchAndAdd(1)) {
            0 -> flowOf(
                ResponsesStreamEvent.OutputItemDone(0, ResponseItem.FunctionCall(
                    name = "exec_command",
                    arguments = buildJsonObject {
                        put("cmd", nestedCommand)
                        put("name", "Check nested Session CLI inheritance")
                        put("yield_time_ms", 10_000)
                    }.toString(),
                    callId = "spawn-fixture-call",
                )),
                ResponsesStreamEvent.Completed(Response(id = "nested-spawn", endTurn = false)),
            )
            1 -> flowOf(
                ResponsesStreamEvent.OutputItemDone(0, ResponseItem.Message(
                    role = MessageRole.Assistant,
                    content = listOf(ContentItem.OutputText("Fixture completed.")),
                )),
                ResponsesStreamEvent.Completed(Response(id = "fixture-complete", endTurn = true)),
            )
            2 -> flow { childEntered.complete(Unit); awaitCancellation() }
            3 -> flow { resumeEntered.complete(Unit); awaitCancellation() }
            else -> error("Unexpected model request in the Session CLI fixture.")
        }
    }) { fixture ->
        val process = ProcessClient()
        try {
            suspend fun invoke(vararg args: String): JsonObject = coroutineScope {
                val task = process.start(ProcessCommand(binary.toString(),
                    args.toList() + listOf("--backend-home", fixture.home.toString()), fixture.launch))
                try {
                    task.stdin.close()
                    val out = async { task.stdout.readFixtureText() }
                    val err = async { task.stderr.readFixtureText() }
                    val output = out.await()
                    assertEquals(0, task.exitCode.await(), "${args.toList()}: ${err.await()}\n$output")
                    val reply = Json.parseToJsonElement(output).jsonObject
                    assertTrue(reply.getValue("ok").jsonPrimitive.boolean, reply.toString())
                    reply.getValue("result").jsonObject
                } finally {
                    withContext(NonCancellable) { task.closeAndJoin() }
                }
            }
            val parent = invoke("session", "spawn", "--model", "gpt-6-astra",
                "--request-user-input-mode", "no_question", "--message", "Run the deterministic fixture.")
            assertTrue(parent.getValue("submitted").jsonPrimitive.boolean)
            val parentId = parent.getValue("root_agent_id").jsonPrimitive.int
            fixture.rpc { rpc ->
                withTimeout(20.seconds) {
                    rpc.runtime.getRunningTurnFlow(parentId).first { !it }
                    while (calls.load() < 2) delay(20)
                }
            }
            val nested = Json.parseToJsonElement(
                SystemCoroutineFileSystem.readString(Path(fixture.launch, "nested-spawn.json")),
            ).jsonObject
            assertTrue(nested.getValue("ok").jsonPrimitive.boolean, nested.toString())
            val child = nested.getValue("result").jsonObject
            val childId = child.getValue("root_agent_id").jsonPrimitive.int
            assertNotEquals(parentId, childId)
            assertFalse(child.getValue("submitted").jsonPrimitive.boolean)
            val status = invoke("agent", "status", "--agent", childId.toString())
            assertEquals(fixture.launch.toString(), status.getValue("cwd").jsonPrimitive.content)
            assertEquals("gpt-6-astra", status.getValue("model").jsonPrimitive.content)
            assertEquals("no_question", status.getValue("request_user_input_mode").jsonPrimitive.content)
            assertEquals(0, status.getValue("latest_index").jsonPrimitive.int)
            val catalog = invoke("session", "list").getValue("sessions").jsonArray
            assertEquals(setOf(parentId, childId), catalog.map { it.jsonObject.getValue("session_id").jsonPrimitive.int }.toSet())
            val target = arrayOf("--agent", childId.toString())
            assertTrue(invoke("agent", "submit", *target, "--message", "Start test work.").getValue("accepted").jsonPrimitive.boolean)
            withTimeout(10.seconds) { childEntered.await() }
            fixture.rpc { rpc -> assertTrue(rpc.runtime.getRunningTurn(childId), "CLI exit must not end accepted work.") }
            assertTrue(invoke("agent", "submit", *target, "--message", "Steer the running turn.").getValue("accepted").jsonPrimitive.boolean)
            suspend fun stopAndObserve() {
                assertEquals(childId, invoke("agent", "stop", *target).getValue("agent_id").jsonPrimitive.int)
                fixture.rpc { rpc -> withTimeout(10.seconds) { rpc.runtime.getRunningTurnFlow(childId).first { !it } } }
            }
            stopAndObserve()
            assertTrue(invoke("agent", "resume", *target).getValue("accepted").jsonPrimitive.boolean)
            withTimeout(10.seconds) { resumeEntered.await() }
            stopAndObserve()
            val beforeCompact = invoke("agent", "status", *target).getValue("latest_index").jsonPrimitive.int
            val compacted = invoke("agent", "compact", *target).getValue("latest_index").jsonPrimitive.int
            assertTrue(compacted > beforeCompact)
            val cleared = invoke("agent", "clear-pending", *target).getValue("latest_index").jsonPrimitive.int
            assertTrue(cleared >= compacted)
        } finally {
            process.close()
        }
    }
}

private suspend fun CoroutineRawSource.readFixtureText(): String {
    val buffer = Buffer()
    while (readAtMostTo(buffer, 8192) != -1L) check(buffer.size <= 64 * 1024)
    return buffer.readString()
}

private class RoundTripFixture(val root: Path, val wire: RoundTripWire, val provider: RoundTripProvider) {
    val home = Path(root, "private-kodex")
    val codex = Path(root, "private-codex")
    val agents = Path(root, "private-agents")
    val launch = Path(root, "launch-base")
    val foreignCwd = Path(root, "different-process-cwd")
    val login = RoundTripLogin(provider)
    lateinit var endpoint: BackendEndpoint

    suspend fun <R> rpc(bound: Boolean = false, block: suspend CoroutineScope.(RpcServices) -> R): R =
        withLocalRpcClient(home, endpoint) { block(RpcServices(RestoringRpcClient(if (bound) it.bindOperation() else it))) }

    suspend fun <R> host(block: suspend CoroutineScope.(RoundTripPage) -> R): R = withLocalFrontendHost(
        endpoint, launch.toString(),
        openResource = { if (it == "index.html") Buffer().apply { writeString("<!doctype html>") } else null },
        newSecret = ::randomBackendPassword,
    ) { launchUrl -> block(bootstrap(launchUrl)) }

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

private suspend fun CoroutineScope.observeRoundTripOAuth(
    events: Flow<OAuthLoginEvent>,
): Pair<OAuthAuthorization, Deferred<Result<Unit>>> {
    val ready = CompletableDeferred<OAuthAuthorization>()
    val observer = async {
        runCatching {
            var committed = false
            events.collect { event ->
                when (event) {
                    is OAuthLoginEvent.Authorization -> check(ready.complete(event.authorization))
                    OAuthLoginEvent.Committed -> {
                        check(ready.isCompleted && !committed)
                        committed = true
                    }
                }
            }
            check(committed)
        }.onFailure { ready.completeExceptionally(it) }
    }
    return ready.await() to observer
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
    suspend fun browserRpc(block: suspend (RpcServices) -> Unit): Unit = coroutineScope {
        val http = HttpClient { install(WebSockets) }
        try {
            val endpoint = info.endpoint
            val socket = http.webSocketSession("ws://127.0.0.1:${endpoint.port}/browser/rpc") {
                header(HttpHeaders.Origin, "http://127.0.0.1:${Url(launchUrl).port}")
            }
            socket.send(Frame.Text(Json.encodeToString(LocalBrowserHello.serializer(), LocalBrowserHello(
                LocalBrowserProtocol, endpoint.instanceId, endpoint.password, endpoint.businessProtocol, info.workingDirectory,
            ))))
            val welcome = Json.decodeFromString(LocalBrowserWelcome.serializer(), assertIs<Frame.Text>(socket.incoming.receive()).readText())
            assertEquals(info.workingDirectory, welcome.workingDirectory)
            val transport = WebSocketRpcTransport(socket, coroutineContext)
            try {
                val client = object : InitializedKrpcClient(
                    rpcClientConfig { serialization { json { serializersModule = RpcWireSerializers } } },
                    transport,
                ) {}
                block(RpcServices(RestoringRpcClient(client)))
            } finally {
                withContext(NonCancellable) { transport.shutdown() }
            }
        } finally {
            http.close()
        }
    }

    suspend fun heartbeat(
        credential: String = info.endpoint.password,
        origin: String = "http://127.0.0.1:${Url(launchUrl).port}",
    ): RoundTripHttpReply = wire.request(
        "http://127.0.0.1:${info.endpoint.port}", "POST", "/heartbeat", origin,
        extraHeaders = listOf(
            "Authorization" to "Bearer $credential",
            "X-Kodex-Instance" to info.endpoint.instanceId,
            "X-Kodex-Protocol" to BackendBusinessProtocol,
            "X-Kodex-Browser-Protocol" to LocalBrowserProtocol.toString(),
        ),
    )
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
    response: suspend (ResponsesApiRequest) -> Flow<ResponsesStreamEvent> = { error("No model turn is authorized by this fixture.") },
    compact: suspend (ResponsesApiRequest) -> RemoteCompactionV2Response = { error("No compaction is authorized by this fixture.") },
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
                        createResponse(response)
                        createRemoteCompactionV2Response(compact)
                    }
                    backend = launch {
                        runBackendHost(
                            f.home, f.codex, f.agents,
                            createClient = { model }, createLoginClient = { f.login },
                        )
                    }
                    BackendControlClient().use { controlClient ->
                        withTimeout(20.seconds) {
                            while (true) {
                                val candidate = try { BackendInstance.discover(f.home) }
                                catch (_: Exception) {
                                    currentCoroutineContext().ensureActive()
                                    null
                                }
                                if (candidate != null && frontendBackendReady(candidate, controlClient)) {
                                    f.endpoint = candidate
                                    break
                                }
                                delay(50)
                            }
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
