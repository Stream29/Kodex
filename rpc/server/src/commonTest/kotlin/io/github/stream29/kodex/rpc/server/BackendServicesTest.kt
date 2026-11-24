@file:OptIn(kotlin.time.ExperimentalTime::class)
@file:Suppress("DEPRECATION")

package io.github.stream29.kodex.rpc.server

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.encodePayloadJson
import io.github.stream29.kodex.agentstorage.sqlite.initializeKodexHomeDatabase
import io.github.stream29.kodex.agentstorage.sqlite.recomputeSessionCatalog
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
import io.github.stream29.kodex.rpc.client.RestoringRpcClient
import io.github.stream29.kodex.rpc.contract.*
import io.github.stream29.kodex.rpc.inmemory.withInMemoryRpc
import io.github.stream29.kodex.rpc.models.*
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.rpcexception.*
import io.github.stream29.kodex.utils.sqlite.openSqliteConnection
import io.ktor.http.URLBuilder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.rpc.withService
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

val backendServicesTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("AgentId RPC resolves actual owners and preserves unrelated same-numbered Sessions") {
        withServices { services, _ ->
            val home = services.settings.contextSettings.value.kodexHome
            SystemCoroutineFileSystem.createDirectories(home)
            openSqliteConnection(Path(home, "kodex.db")).use { db ->
                initializeKodexHomeDatabase(db)
                db.execute("INSERT INTO sessions(session_id) VALUES(12), (90)")
                db.execute("INSERT INTO agents(agent_id, session_id, parent_agent_id) VALUES(90, 12, NULL), (12, 90, NULL), (91, 12, 90)")
                for ((id, title) in listOf(90L to "Owner of Session 12", 12L to "Unrelated owner", 91L to "The child")) {
                    db.execute(
                        """INSERT INTO agent_commits VALUES(?, 'settings', 0, ?, '2026-11-18T00:00:00Z')""",
                        listOf(id, KodexAgentSettings(
                            model = OpenAiModelId("test-model"), name = title,
                            requestUserInputMode = RequestUserInputMode.AskUser,
                        ).encodePayloadJson()),
                    )
                }
                db.execute("INSERT INTO session_catalog_view(root_agent_id) VALUES(90), (12)")
                db.recomputeSessionCatalog(90)
                db.recomputeSessionCatalog(12)
            }
            withInMemoryRpc(services::register) { raw ->
                val client = RestoringRpcClient(raw)
                val global = client.withService<GlobalRpc>()
                val runtime = client.withService<AgentRuntimeRpc>()
                assertEquals(90, global.getRootAgentId(12))
                assertEquals(12, global.getRootAgentId(90))
                assertFailsWith<SessionNotFound> { global.getRootAgentId(91) }
                assertFailsWith<AgentNotFound> { runtime.keepAgentAlive(999) }
                assertFailsWith<AgentNotActive> { runtime.getLatestIndex(90) }
                assertEquals(
                    mapOf(12 to "Owner of Session 12", 90 to "Unrelated owner"),
                    global.getSessionCatalog(true).associate { it.sessionIndex to it.name },
                )
                assertFalse(global.getSessionCatalog(true).any { it.isActive })
                runtime.keepAgentAlive(91)
                assertFalse(global.getSessionCatalog(true).any { it.isActive })
                runtime.keepAgentAlive(90)
                assertEquals(12, global.getSessionCatalog(true).single { it.isActive }.sessionIndex)
                assertEquals(90, global.getSessionCatalog(true).single { it.isActive }.rootAgentId)
                assertFailsWith<AgentNotActive> { runtime.getLatestIndex(12) }
                runtime.completeAgent(90)
                assertEquals(listOf(12), global.getSessionCatalog(true).filter { it.archived }.map { it.sessionIndex })
                assertEquals(listOf(90), global.getSessionCatalog(false).map { it.sessionIndex })
                global.unarchiveSession(12)
                global.archiveSession(12)
                assertEquals(listOf(12), global.getSessionCatalog(true).filter { it.archived }.map { it.sessionIndex })
                global.unarchiveSession(12)
                openSqliteConnection(Path(home, "kodex.db")).use { db ->
                    assertEquals(listOf(listOf(0L)), db.query("SELECT completed FROM agents WHERE agent_id = 90"))
                }
                val delivered = async(start = CoroutineStart.UNDISPATCHED) {
                    services.notifications.filterIsInstance<Notification.AskUserCreated>().first()
                }
                runtime.createAskUser(91, RequestUserInputArgs(listOf(RequestUserInputQuestion(
                    id = "choice", header = "Choice", question = "Choose a value",
                ))))
                val ask = withTimeout(5.seconds) { delivered.await() }
                assertEquals(12, ask.sessionIndex)
                assertEquals(91, ask.agentId)
                val trigger = Instant.parse("2031-01-01T00:00:00Z")
                global.createSchedule(91, trigger, null, "child schedule")
                val unrelated = global.createSchedule(12, trigger, null, "unrelated schedule")
                assertTrue(global.deleteSession(12))
                assertTrue(global.listSchedules(91).isEmpty())
                assertEquals(listOf(unrelated), global.listSchedules(12))
                assertFailsWith<AgentNotFound> { runtime.getLatestIndex(91) }
                assertEquals(12, global.getRootAgentId(90))
            }
        }
    }
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
                val runtime = client.withService<AgentRuntimeRpc>()
                val staleFrontend = global.getSettings()
                val firstCwd = Path("/fixture/旧目录 with spaces")
                val first = global.getRootAgentId(global.createSession(firstCwd))
                val firstNonce = runtime.getCacheNonce(first)
                val original = runtime.getLastCommitsIn(first, firstNonce, 0, 0, AgentCommitType.SettingsTypes, 1).single().commit as KodexAgentSettings
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
                val secondSession = global.createSession(cwd)
                val second = global.getRootAgentId(secondSession)
                val current = runtime.getLastCommitsIn(second, runtime.getCacheNonce(second), 0, 0, AgentCommitType.SettingsTypes, 1).single().commit as KodexAgentSettings
                assertEquals(defaults.model, current.model)
                assertEquals(defaults.reasoningEffort, current.reasoning.effort)
                assertEquals(defaults.serviceTier, current.serviceTier)
                assertEquals(defaults.requestUserInputMode, current.requestUserInputMode)
                assertEquals(cwd, current.cwd)
                assertEquals("Session $secondSession", current.name)
                assertNotEquals(original.turnId, current.turnId)
                assertNotEquals(original.windowId, current.windowId)
                assertEquals(0, services.runtime.getLatestIndex(second))
                assertFalse(services.runtime.getRunningTurn(second))
                assertEquals(firstCwd, original.cwd)
                assertEquals(staleFrontend.newSession.model, original.model)
                assertEquals(original, runtime.getLastCommitsIn(first, firstNonce, 0, 0, AgentCommitType.SettingsTypes, 1).single().commit as KodexAgentSettings)
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
                assertTrue(runCatching { global.getRootAgentId(index) }.isFailure)
                assertTrue(runCatching { global.compareAndSetSettings(before, before.copy(daemonTtlSeconds = 120)) }.isFailure)
                assertTrue(runCatching { global.compareAndSetFrontendSettings(front, front.copy(newLineKey = io.github.stream29.kodex.cli.settings.NewLineKey.Enter)) }.isFailure)
                assertTrue(runCatching { global.validateDirectory(DirectoryQuery(".", ".")) }.isFailure)
                assertTrue(runCatching { global.listDirectories(DirectoryQuery(".", ".")) }.isFailure)
                assertTrue(runCatching { global.evaluateContextPaths(ContextPathQuery("/", emptyList(), ".")) }.isFailure)
                assertTrue(runCatching { global.startOAuthLogin(OAuthTarget.OpenAi(KodexAuthSource.Kodex)).first() }.isFailure)
                assertEquals(8, rejected)
                assertEquals(before, services.settings.unified.current.backend)
                assertEquals(front, services.settings.unified.current.frontend)
            }
        }
    }
    test("the complete backend registers both real services and retains independent views") {
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
                val agentId = global.getRootAgentId(index)
                runtime.keepAgentAlive(agentId)
                assertEquals(0, runtime.getLatestIndex(agentId))
                assertTrue(global.getSessionCatalog(false).single().isActive)
                val nonce = runtime.getCacheNonce(agentId)
                assertEquals(nonce, runtime.getCacheNonceFlow(agentId).first())
                for (types in listOf(
                    AgentCommitType.IndexTypes,
                    AgentCommitType.WorkTypes,
                    AgentCommitType.SettingsTypes,
                    AgentCommitType.LlmRequestMetaTypes,
                    AgentCommitType.UnstableTypes,
                )) {
                    runtime.getFirstCommitsIn(agentId, nonce, 0, 1, types)
                }
                global.archiveSession(index)
                assertTrue(global.getSessionCatalog(false).isEmpty())
                assertTrue(global.getSessionCatalog(true).single().archived)
                global.unarchiveSession(index)
                val copy = global.forkAgent(agentId)
                assertNotEquals(index, copy)
                assertTrue(global.deleteSession(copy))
                assertTrue(global.deleteSession(index))
                assertFalse(global.deleteSession(index))
                assertFailsWith<SessionNotFound> { global.getRootAgentId(index) }
                assertFailsWith<AgentNotFound> { runtime.getLatestIndex(agentId) }
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
                val agentId = global.getRootAgentId(index)
                openSqliteConnection(Path(home, "kodex.db"), readOnly = true).use { db ->
                    assertEquals(listOf(listOf(index.toLong())), db.query("SELECT session_id FROM sessions"))
                    runtime.submit(agentId, listOf(ContentItem.InputText("SQLite mutation")))
                    runtime.getRunningTurnFlow(agentId).first { !it }
                    val committedRow = db.query(
                        """SELECT "index", payload_json FROM agent_commits WHERE agent_id = ? AND type = 'user_message'""",
                        listOf(agentId.toLong()),
                    ).single()
                    val state = committedRow[0] as Long
                    val payload = committedRow[1] as String
                    assertTrue(payload.contains("SQLite mutation"))
                    val message = Path(home, "sessions", index.toString(), "index", "$state.json")
                    mirrored { fs.metadataOrNull(message) != null && fs.readString(message).contains("SQLite mutation") }
                    global.archiveSession(index)
                    assertEquals(
                        listOf(listOf(1L)),
                        db.query("SELECT completed FROM agents WHERE agent_id = ?", listOf(agentId.toLong())),
                    )
                    val marker = Path(home, "sessions", index.toString(), "archive.mark")
                    mirrored { fs.metadataOrNull(marker) != null }
                    global.unarchiveSession(index)
                    mirrored { fs.metadataOrNull(marker) == null }
                    val fork = global.forkAgent(agentId)
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
                val agentId = global.getRootAgentId(index)
                val event = async(start = CoroutineStart.UNDISPATCHED) { services.notifications.first() }
                services.notificationSubscriptionCount.first { it > 0 }
                runtime.submit(agentId, listOf(ContentItem.InputText("question")))
                val stop = assertIs<Notification.Stop.AssistantMessage>(event.await())
                assertEquals(index, stop.sessionIndex)
                assertEquals(agentId, stop.agentId)
                assertEquals("answer", stop.message.content.filterIsInstance<ContentItem.OutputText>().single().text)
                runtime.getRunningTurnFlow(agentId).first { !it }
            }
        }
    }

    test("shared SQLite schedule repository fires due schedule through AgentEventBus and cleans one-shot row") {
        withServices { services, _ ->
            withInMemoryRpc(services::register) { raw ->
                val client = RestoringRpcClient(raw)
                val global = client.withService<GlobalRpc>()
                val runtime = client.withService<AgentRuntimeRpc>()
                val sessionId = global.createSession(Path("."))
                val index = global.getRootAgentId(sessionId)
                val event = async(start = CoroutineStart.UNDISPATCHED) { services.notifications.first() }
                services.notificationSubscriptionCount.first { it > 0 }

                val now = kotlin.time.Clock.System.now()
                val created = services.schedules.create(
                    agentId = index,
                    triggerAt = now,
                    cron = null,
                    description = "daily check-in",
                    createdAt = now,
                )
                val stop = assertIs<Notification.Stop.AssistantMessage>(event.await())
                assertEquals(sessionId, stop.sessionIndex)
                assertEquals(index, stop.agentId)
                runtime.getRunningTurnFlow(index).first { !it }
                withTimeout(5.seconds) {
                    while (services.schedules.list(index).isNotEmpty()) delay(10)
                }
                val nonce = runtime.getCacheNonce(index)
                val scheduleCommits = runtime.getFirstCommitsIn(
                    index,
                    nonce,
                    0,
                    runtime.getLatestIndex(index),
                    setOf(AgentCommitType.EventSchedule),
                    10,
                )
                assertEquals(1, scheduleCommits.size)
                val committed = assertIs<io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableScheduleEvent>(
                    scheduleCommits.single().commit,
                )
                assertEquals(created.scheduleId, committed.scheduleId)
                assertEquals("daily check-in", committed.description)
            }
        }
    }

    test("schedule runner retains due schedule on transient delivery failure and drops orphan schedule for missing agent") {
        var failDeliveryCallsRemaining = 0
        var injectedFailures = 0
        var gateArmed = false
        var callsSinceArmed = 0
        withServices(checkAdmission = {
            if (gateArmed && failDeliveryCallsRemaining > 0) {
                callsSinceArmed++
                // Allow the runner's batch pre-check (1) and per-record pre-check (2),
                // then fail inside submitScheduleTrigger -> sessionRepository.open (3).
                if (callsSinceArmed >= 3) {
                    failDeliveryCallsRemaining--
                    injectedFailures++
                    error("Transient delivery failure")
                }
            }
        }) { services, _ ->
            withInMemoryRpc(services::register) { raw ->
                val client = RestoringRpcClient(raw)
                val global = client.withService<GlobalRpc>()
                val runtime = client.withService<AgentRuntimeRpc>()
                val sessionId = global.createSession(Path("."))
                val index = global.getRootAgentId(sessionId)
                val event = async(start = CoroutineStart.UNDISPATCHED) { services.notifications.first() }
                services.notificationSubscriptionCount.first { it > 0 }

                val now = kotlin.time.Clock.System.now()
                // Orphan schedule for nonexistent agent should be cleaned up instead of spinning forever.
                services.schedules.create(
                    agentId = index + 9999,
                    triggerAt = now,
                    cron = null,
                    description = "orphan",
                    createdAt = now,
                )
                withTimeout(5.seconds) {
                    while (services.schedules.list(index + 9999).isNotEmpty()) delay(10)
                }

                // Arm transient failure for the first delivery attempt of a valid due schedule,
                // and schedule a second due entry + concurrent timer update to ensure nextTriggerAt
                // changes never cancel acknowledgement or duplicate delivery.
                val firstDueAt = kotlin.time.Clock.System.now() + 80.milliseconds
                val firstCreated = services.schedules.create(
                    agentId = index,
                    triggerAt = firstDueAt,
                    cron = null,
                    description = "retained-on-transient-failure",
                    createdAt = now,
                )
                val secondCreated = services.schedules.create(
                    agentId = index,
                    triggerAt = firstDueAt + 5.milliseconds,
                    cron = null,
                    description = "second-due-without-duplicate",
                    createdAt = now,
                )
                callsSinceArmed = 0
                failDeliveryCallsRemaining = 1
                gateArmed = true

                val stop = assertIs<Notification.Stop.AssistantMessage>(event.await())
                assertEquals(sessionId, stop.sessionIndex)
                assertEquals(index, stop.agentId)
                assertEquals(1, injectedFailures)
                withTimeout(5.seconds) {
                    while (services.schedules.list(index).isNotEmpty()) delay(10)
                }
                withTimeout(5.seconds) {
                    while (true) {
                        val currentNonce = runtime.getCacheNonce(index)
                        val count = runtime.getFirstCommitsIn(
                            index,
                            currentNonce,
                            0,
                            runtime.getLatestIndex(index),
                            setOf(AgentCommitType.EventSchedule),
                            10,
                        ).size
                        if (count >= 2 && !runtime.getRunningTurn(index)) break
                        delay(10)
                    }
                }
                delay(50)
                val nonce = runtime.getCacheNonce(index)
                val scheduleCommits = runtime.getFirstCommitsIn(
                    index,
                    nonce,
                    0,
                    runtime.getLatestIndex(index),
                    setOf(AgentCommitType.EventSchedule),
                    10,
                )
                assertEquals(2, scheduleCommits.size)
                val descriptions = scheduleCommits.map {
                    assertIs<io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableScheduleEvent>(it.commit)
                }
                assertEquals(
                    listOf(firstCreated.scheduleId to "retained-on-transient-failure", secondCreated.scheduleId to "second-due-without-duplicate"),
                    descriptions.map { it.scheduleId to it.description },
                )
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
        withBackendServicesWithTitleGenerator(
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
