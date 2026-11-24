package io.github.stream29.kodex.backend.host

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.addPermits
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.sqlite.SqliteKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.UnstableSnapshot
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingAwait
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCommandExecution
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCommandExecutionAction
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.app.migration.prepareKodexHome
import io.github.stream29.kodex.backend.BackendEndpoint
import io.github.stream29.kodex.backend.BackendInstance
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.MessagePhase
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.rpc.client.RestoringRpcClient
import io.github.stream29.kodex.rpc.contract.AgentRuntimeRpc
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import io.github.stream29.kodex.rpc.local.withLocalRpcClient
import io.github.stream29.kodex.tool.awaitevent.AwaitEventArguments
import io.github.stream29.kodex.tool.awaitevent.AwaitEventInput
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.kodex.utils.coroutines.use
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.osenvironment.environmentVariable
import io.github.stream29.kodex.utils.shellclient.Shell
import io.github.stream29.kodex.utils.shellclient.ShellType
import io.github.stream29.kodex.utils.shellclient.renderedBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.rpc.withService
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val FixtureRoot = "KODEX_RESIDENCY_FIXTURE_ROOT"
private const val PtyCloseSlots = 12
private const val PtyCloseReady = "FIXTURE-PTY-CLOSE-READY"
private const val PtyCloseTail = "FIXTURE-PTY-CLOSE-TAIL"
private val fs = SystemCoroutineFileSystem

// Opt-in driver for scripts/measure-backend-residency.py. Never starts a daemon or reads a user Home.
val backendResidencyFixtureTest by testSuite(
    compartment = { TestCompartment.RealTime },
    testConfig = TestConfig.testScope(isEnabled = false, timeout = 5.minutes)
        .addPermits(TestConfig.Permit.SuiteWithoutChildren),
) {
    val requested = environmentVariable(FixtureRoot)
    if (requested != null) test("seed or exercise the isolated daemon residency fixture") {
        val root = fs.resolve(Path(requested))
        require(root.toString() == requested)
        require(Regex("/tmp/kodex-residency-[a-z0-9_]+/(release|candidate|default|pty-close|signal-(term|int|stop))").matches(requested))
        check(fs.readString(Path(root, "fixture")) == "kodex-residency-fixture-1\n")
        val mode = checkNotNull(environmentVariable("KODEX_RESIDENCY_MODE"))
        val smoke = environmentVariable("KODEX_RESIDENCY_SMOKE") == "1"
        require(requested.endsWith("/pty-close") == (mode == "seed-pty-close" || mode == "load-pty-close"))
        when (mode) {
            "seed" -> seed(root, smoke, defaultRun = false)
            "seed-default" -> seed(root, smoke, defaultRun = true)
            "seed-empty" -> {
                val home = home(root)
                check(!fs.exists(home)) { "Refusing to seed an existing Home." }
                prepareKodexHome(home).closeAndJoin()
            }
            "warm" -> loadHistory(root, fixtureEndpoint(root), ids(root, "history").first(), if (smoke) 8 else 240)
            "load" -> runLoad(root, smoke)
            "seed-pty-close" -> seedPtyCloseDiagnostic(root)
            "load-pty-close" -> runPtyCloseDiagnostic(root)
            else -> error("Unknown daemon residency fixture mode: $mode")
        }
        fs.writeString(Path(root, "$mode-complete"), "ok\n")
    }
}

private fun home(root: Path) = Path(root, "os-home", ".kodex")
private suspend fun fixtureEndpoint(root: Path): BackendEndpoint = home(root).let { home ->
    val endpoint = checkNotNull(BackendInstance.discover(home)) { "Fixture backend is not running." }
    connectBackend(home, endpoint.port)
}

private suspend fun seed(root: Path, smoke: Boolean, defaultRun: Boolean) = coroutineScope {
    val home = home(root)
    check(!fs.exists(home)) { "Refusing to seed an existing Home." }
    val work = Path(root, "work")
    prepareKodexHome(home).closeAndJoin()
    // No daemon is running during seed; the repository is closed before starting either binary.
    run {
        testKodexAgentDependencies().use { dependencies ->
            SqliteKodexSessionRepository(home, dependencies).use { repository ->
                val model = OpenAiModelId("offline-residency-fixture")
                suspend fun next(name: String) = repository.create().let { sessionId ->
                    val session = repository.open(sessionId)
                    session.runtime.modify { it.initialize(KodexAgentSettings(model, cwd = work, name = name)) }
                    sessionId to session
                }
                if (defaultRun) {
                    val (scheduleSession, _) = next("Schedule fixture")
                    val scheduleAgent = repository.getRootAgentId(scheduleSession)
                    val (awaitSession, session) = next("Await fixture")
                    val awaitAgent = repository.getRootAgentId(awaitSession)
                    val deadline = Clock.System.now() + 330.seconds
                    session.runtime.modify {
                        it.commit(UnstableSnapshot(listOf(PendingAwait(
                            callId = "residency-await",
                            arguments = AwaitEventArguments(listOf(AwaitEventInput.Timeout(330_000)), name = "Wait for the fixture deadline"),
                            timeoutAt = deadline,
                            armedForAgentId = awaitAgent,
                        ))))
                    }
                    assertEquals(deadline, repository.pendingAwaitDeadlines.value[awaitAgent]?.timeoutAt)
                    fs.writeString(Path(root, "default-ids"), "$scheduleAgent\n$awaitAgent\n$deadline\n")
                } else {
                    val ptyCount = if (smoke) 1 else 12
                    val history = List((if (smoke) 9 else 296) - ptyCount) { ordinal ->
                        val (sessionId, session) = next("Synthetic history $ordinal")
                        session.runtime.modify { storage ->
                            repeat(if (smoke) 8 else 240) { index ->
                                val text = "Synthetic session $ordinal, entry $index. " + ("x".repeat(if (smoke) 256 else 2000))
                                storage.commit(if (index % 2 == 0) {
                                    StableUserMessage(listOf(ContentItem.InputText(text)))
                                } else {
                                    StableAssistantMessage(listOf(ContentItem.OutputText(text)), phase = MessagePhase.FinalAnswer)
                                })
                            }
                        }
                        repository.closeSession(sessionId)
                        repository.getRootAgentId(sessionId)
                    }
                    val terminals = List(ptyCount) { ordinal ->
                        val (sessionId, session) = next("PTY fixture $ordinal")
                        session.runtime.modify {
                            it.commit(StableUserMessage(listOf(ContentItem.InputText("Run the offline fixture"))))
                            it.commit(UnstableSnapshot(listOf(PendingCommandExecution(
                                callId = "residency-pty-$ordinal",
                                action = PendingCommandExecutionAction.ExecCommand(ExecCommandArguments(
                                    command = """test -t 0 || exit 7; printf 'FIXTURE-PTY\n'; IFS= read -r value; test "${'$'}value" = finish""",
                                    name = "Hold a fixture PTY open",
                                    shell = Shell(ShellType.Sh, Path("/bin/sh")),
                                    yieldTimeMillis = 100,
                                )),
                            ))))
                        }
                        repository.closeSession(sessionId)
                        repository.getRootAgentId(sessionId)
                    }
                    fs.writeString(Path(root, "history"), history.joinToString("\n"))
                    fs.writeString(Path(root, "terminals"), terminals.joinToString("\n"))
                }
            }
        }
    }
}

private suspend fun ids(root: Path, file: String): List<Int> =
    fs.readString(Path(root, file)).lineSequence().filter { it.isNotEmpty() }.map { it.toInt() }.toList()

private suspend fun loadHistory(
    root: Path,
    endpoint: BackendEndpoint,
    agentId: Int,
    historySize: Int,
    fork: Boolean = false,
    linger: suspend () -> Unit = {},
) = withLocalRpcClient(home(root), endpoint) { connection ->
    val client = RestoringRpcClient(connection)
    val runtime = client.withService<AgentRuntimeRpc>()
    val global = client.withService<GlobalRpc>()
    runtime.keepAgentAlive(agentId)
    val nonce = runtime.getCacheNonce(agentId)
    val latest = runtime.getLatestIndex(agentId)
    val history = runtime.getLastCommitsIn(agentId, nonce, 0, latest, AgentCommitType.IndexTypes, historySize)
    assertEquals(historySize, history.size)
    if (fork) {
        val copy = global.forkAgentHistory(agentId, latest + 1, nonce)
        assertTrue(global.deleteSession(copy))
    }
    linger()
}

private suspend fun runLoad(root: Path, smoke: Boolean) = withLocalRpcClient(home(root), fixtureEndpoint(root)) { connection ->
    val runtime = RestoringRpcClient(connection).withService<AgentRuntimeRpc>()
    val terminalAgents = ids(root, "terminals")
    val keepAlive = launch {
        while (true) {
            terminalAgents.forEach { runtime.keepAgentAlive(it) }
            delay(15.seconds)
        }
    }
    try {
        val terminals = terminalAgents.map { agent -> async {
            runtime.keepAgentAlive(agent)
            assertTrue(runtime.requestResume(agent))
            while (true) {
                val running = runtime.getShellSessions(agent).entries.singleOrNull { it.value.exitCode == null }
                if (running != null && runtime.readShellSessionOutput(agent, running.key)
                        .renderedBytes().decodeToString().contains("FIXTURE-PTY")
                ) return@async agent to running.key
                delay(50)
            }
            @Suppress("UNREACHABLE_CODE")
            error("Terminal loop returned without a running PTY.")
        } }.awaitAll()
        fs.writeString(Path(root, "terminals-ready"), "${terminals.size}\n")
        val history = ids(root, "history")
        var cursor = 0
        val endpoint = fixtureEndpoint(root)
        for ((round, parallelism) in (if (smoke) listOf(1, 1, 1, 2) else listOf(5, 5, 5, 12)).withIndex()) {
            coroutineScope {
                val release = CompletableDeferred<Unit>()
                val ready = List(parallelism) { CompletableDeferred<Unit>() }
                val readers = ready.map { barrier ->
                    val agentId = history[cursor++ % history.size]
                    async {
                        loadHistory(root, endpoint, agentId, if (smoke) 8 else 240, fork = true) {
                            barrier.complete(Unit)
                            release.await()
                        }
                    }
                }
                ready.forEach { it.await() }
                fs.writeString(Path(root, "round-$round-active"), "$parallelism\n")
                while (!fs.exists(Path(root, "round-$round-release"))) delay(50)
                release.complete(Unit)
                readers.awaitAll()
            }
            fs.writeString(Path(root, "round-$round-disconnected"), "ok\n")
        }
        terminals.map { (agent, session) -> async {
            runtime.writeShellSessionStdin(agent, session, "finish\n")
            while (true) {
                val state = checkNotNull(runtime.getShellSessions(agent)[session])
                if (state.exitCode != null) {
                    assertEquals(0, state.exitCode)
                    break
                }
                delay(50)
            }
        } }.awaitAll()
    } finally {
        keepAlive.cancelAndJoin()
    }
}

private suspend fun seedPtyCloseDiagnostic(root: Path) = coroutineScope {
    val home = home(root)
    check(!fs.exists(home)) { "Refusing to seed an existing Home." }
    val work = Path(root, "work")
    prepareKodexHome(home).closeAndJoin()
    testKodexAgentDependencies().use { dependencies ->
        SqliteKodexSessionRepository(home, dependencies).use { repository ->
            val terminals = List(PtyCloseSlots) { slot ->
                val id = repository.create()
                val session = repository.open(id)
                session.runtime.modify { storage ->
                    storage.initialize(KodexAgentSettings(OpenAiModelId("offline-residency-fixture"), cwd = work, name = "PTY diagnostic $slot"))
                    // Bootstrap only the pending turn; no history payload or configured model provider.
                    storage.commit(StableUserMessage(listOf(ContentItem.InputText("Run the offline PTY diagnostic"))))
                    storage.commit(UnstableSnapshot(listOf(PendingCommandExecution(
                        callId = "pty-close-$slot",
                        action = PendingCommandExecutionAction.ExecCommand(ExecCommandArguments(
                            command = """test -t 0 || exit 7; printf '$PtyCloseReady\n'; IFS= read -r value && test "${'$'}value" = finish || exit 8; printf '$PtyCloseTail\n'""",
                            name = "Close the diagnostic PTY",
                            shell = Shell(ShellType.Sh, Path("/bin/sh")),
                            yieldTimeMillis = 100,
                        )),
                    ))))
                }
                repository.closeSession(id)
                repository.getRootAgentId(id)
            }
            fs.writeString(Path(root, "terminals"), terminals.joinToString("\n"))
        }
    }
}

private class PtyCloseEvidence(private val root: Path) {
    suspend fun mark(slot: String, phase: String, result: String) =
        fs.writeString(Path(root, "pty-evidence-$slot-$phase"), "$result\n")

    suspend fun <T> at(slot: String, phase: String, shellId: Int? = null, block: suspend () -> T): T {
        val identity = shellId?.let { "pty:$it;" }.orEmpty()
        mark(slot, phase, "${identity}started")
        try {
            val result = block()
            mark(slot, phase, "${identity}ok")
            return result
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try {
                    val type = failure::class.simpleName ?: "Throwable"
                    val result = if (failure is CancellationException &&
                        type != "TimeoutCancellationException"
                    ) "cancelled" else "error:$type"
                    mark(slot, phase, "$identity$result")
                } catch (diagnosticFailure: Throwable) {
                    if (diagnosticFailure !== failure) failure.addSuppressed(diagnosticFailure)
                }
            }
            throw failure
        }
    }
}

private suspend fun runPtyCloseDiagnostic(root: Path) {
    val evidence = PtyCloseEvidence(root)
    val agents = ids(root, "terminals")
    check(agents.size == PtyCloseSlots && agents.distinct().size == PtyCloseSlots)
    evidence.at("all", "client-scope") {
        withLocalRpcClient(home(root), fixtureEndpoint(root)) { connection ->
            val runtime = RestoringRpcClient(connection).withService<AgentRuntimeRpc>()
            val keepAlive = launch {
                evidence.at("all", "keepalive-loop") {
                    while (true) {
                        agents.forEach { runtime.keepAgentAlive(it) }
                        delay(15.seconds)
                    }
                }
            }
            try {
                val ready = agents.mapIndexed { slot, agent -> async {
                    val label = slot.toString()
                    evidence.at(label, "resume-ack") {
                        withTimeout(20.seconds) { check(runtime.requestResume(agent)) }
                    }
                    evidence.at(label, "ready-marker") {
                        withTimeout(20.seconds) {
                            while (true) {
                                val live = runtime.getShellSessions(agent).entries.singleOrNull { it.value.exitCode == null }
                                if (live != null && runtime.readShellSessionOutput(
                                        agent, live.key, limit = 512u, expectedRegistrationNonce = live.value.registrationNonce,
                                    ).renderedBytes().decodeToString().contains(PtyCloseReady)
                                ) {
                                    evidence.mark(label, "registration", "pty:${live.key};ready")
                                    return@withTimeout live
                                }
                                delay(50)
                            }
                            @Suppress("UNREACHABLE_CODE")
                            error("PTY readiness did not complete.")
                        }
                    }
                } }.awaitAll()
                evidence.mark("all", "ready-barrier", "ok")
                agents.mapIndexed { slot, agent -> async {
                    val label = slot.toString()
                    val (shell, initial) = ready[slot]
                    evidence.at(label, "stdin-ack", shell) {
                        withTimeout(15.seconds) { runtime.writeShellSessionStdin(agent, shell, "finish\n") }
                    }
                    evidence.at(label, "registry-lookup", shell) {
                        withTimeout(15.seconds) {
                            val state = runtime.getShellSessions(agent)[shell]
                            if (state == null) {
                                evidence.mark(label, "registry-observation", "pty:$shell;missing")
                                error("PTY registration is missing.")
                            }
                            if (state.registrationNonce != initial.registrationNonce) {
                                evidence.mark(label, "registry-observation", "pty:$shell;changed")
                                error("PTY registration changed.")
                            }
                            evidence.mark(label, "registry-observation", "pty:$shell;found")
                        }
                    }
                    evidence.at(label, "exit-observation", shell) {
                        withTimeout(15.seconds) {
                            while (true) {
                                val state = runtime.getShellSessions(agent)[shell]
                                if (state == null || state.registrationNonce != initial.registrationNonce) {
                                    evidence.mark(label, "registry-observation", "pty:$shell;lost")
                                    error("PTY registration was lost before exit.")
                                }
                                val code = state.exitCode
                                if (code != null) {
                                    evidence.mark(label, "exit-code", "pty:$shell;code:$code")
                                    assertEquals(0, code)
                                    break
                                }
                                delay(50)
                            }
                        }
                    }
                    evidence.at(label, "tail-check", shell) {
                        withTimeout(15.seconds) {
                            val seen = runtime.readShellSessionOutput(
                                agent, shell, limit = 512u, expectedRegistrationNonce = initial.registrationNonce,
                            ).renderedBytes().decodeToString().contains(PtyCloseTail)
                            evidence.mark(label, "tail-marker", "pty:$shell;${if (seen) "seen" else "missing"}")
                            check(seen)
                        }
                    }
                } }.awaitAll()
            } finally {
                withContext(NonCancellable) {
                    try {
                        evidence.at("all", "keepalive-close") { keepAlive.cancelAndJoin() }
                    } finally {
                        evidence.mark("all", "client-close", "started")
                    }
                }
            }
        }
    }
    evidence.mark("all", "client-close", "ok")
}
