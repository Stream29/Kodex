package io.github.stream29.kodex.backend.host

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.sqlite.recomputeSessionCatalog
import io.github.stream29.kodex.app.history.contract.AgentHistoryLoadState
import io.github.stream29.kodex.app.migration.KodexHomeVersionException
import io.github.stream29.kodex.backend.BackendInstance
import io.github.stream29.kodex.cli.rpc.RpcServices
import io.github.stream29.kodex.cli.rpc.RpcSessionViews
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.ResponsesApiRequest
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.rpc.client.RestoringRpcClient
import io.github.stream29.kodex.rpc.contract.AgentRuntimeRpc
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import io.github.stream29.kodex.rpc.local.withLocalRpcClient
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.sqlite.openSqliteConnection
import kotlin.random.Random
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.rpc.withService

val backendHistoryStartupTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("nonempty history reaches its full initial window over a real native connection promptly") {
        withHost { home, endpoint, _ ->
            openSqliteConnection(Path(home, "kodex.db")).use { db ->
                db.transaction {
                    db.execute("INSERT INTO sessions(session_id) VALUES (0)")
                    db.execute("INSERT INTO agents(agent_id, session_id) VALUES (0, 0)")
                    db.execute("INSERT INTO session_catalog_view(root_agent_id) VALUES (0)")
                    db.execute("INSERT INTO agent_commits VALUES (0, 'settings', 0, ?, '2026-01-01T00:00:00Z')",
                        listOf("""{"model":"fixture","name":"Synthetic history"}"""))
                    repeat(240) { index ->
                        db.execute("INSERT INTO agent_commits VALUES (0, 'user_message', ?, ?, '2026-01-01T00:00:00Z')", listOf(index.toLong(),
                            """{"type":"user_message","content":[{"type":"input_text","text":"Fixture $index"}]}"""))
                    }
                    db.recomputeSessionCatalog(0)
                }
            }
            withLocalRpcClient(home, endpoint) { connection ->
                val views = RpcSessionViews(
                    this, RpcServices(RestoringRpcClient(connection)), MutableStateFlow(emptyList()),
                    reportUnhandledError = { throw it },
                )
                try {
                    // Native Debug executes this full 192-item window more slowly than Release.
                    withTimeout(10.seconds) {
                        val view = views.open(0)
                        val agent = requireNotNull(view.rootAgent.value)
                        val state = agent.history.loadState.first { it != AgentHistoryLoadState.Initializing }
                        assertEquals(AgentHistoryLoadState.Ready, state)
                        assertEquals(192, agent.history.historyItems.value.size)
                        assertTrue(agent.history.historyItems.value.hasOlder)
                    }
                } finally {
                    views.close()
                    views.join()
                }
            }
        }
    }

    test("the current catalog serves SQLite history and mirrors new messages without importing mirrors") {
        val request = CompletableDeferred<ResponsesApiRequest>()
        withHost(prepareHome = { home ->
            val fs = SystemCoroutineFileSystem
            val session = Path(home, "sessions", "0")
            for (name in listOf("index", "work", "settings", "timestamp", "token-count", "unstable")) {
                fs.createDirectories(Path(session, name))
                fs.writeString(Path(session, name, "latest.json"), "-1")
            }
            openSqliteConnection(Path(home, "kodex.db")).use { db ->
                db.transaction {
                    db.execute("INSERT INTO sessions(session_id) VALUES (0)")
                    db.execute("INSERT INTO agents(agent_id, session_id, parent_agent_id) VALUES (0, 0, NULL)")
                    db.execute("INSERT INTO session_catalog_view(root_agent_id) VALUES (0)")
                    db.execute(
                        "INSERT INTO agent_commits VALUES (0, 'settings', 0, ?, '2026-01-01T00:00:00Z')",
                        listOf("""{"model":"fixture","name":"Current session"}"""),
                    )
                    db.execute(
                        "INSERT INTO agent_commits VALUES (0, 'user_message', 0, ?, '2026-01-01T00:00:00Z')",
                        listOf("""{"type":"user_message","content":[{"type":"input_text","text":"Stored history"}]}"""),
                    )
                    db.recomputeSessionCatalog(0)
                }
            }
            // Startup and RPC history reads must not use the retained mirror.
            fs.writeString(Path(session, "index", "0.json"), "corrupted mirror")
        }, stream = {
            flow { emit(ResponsesStreamEvent.Completed(Response(id = "r", endTurn = true))) }
        }, observeRequest = { request.complete(it) }) { home, endpoint, _ ->
            withLocalRpcClient(home, endpoint) { connection ->
                val client = RestoringRpcClient(connection)
                val global = client.withService<GlobalRpc>()
                val runtime = client.withService<AgentRuntimeRpc>()
                assertEquals("Current session", global.getSessionCatalog(false).single().name)
                assertEquals("Current session", global.queryAgentSql("SELECT name FROM session_catalog_view")
                    .rows?.single()?.single()?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
                runtime.keepAgentAlive(global.getRootAgentId(0))
                val nonce = runtime.getCacheNonce(0)
                assertEquals(
                    "Current session",
                    (runtime.getLastCommitsIn(0, nonce, 0, 0, AgentCommitType.SettingsTypes, 1).single().commit as KodexAgentSettings).name,
                )
                assertEquals(
                    listOf(ContentItem.InputText("Stored history")),
                    assertIs<StableUserMessage>(runtime.getLastCommitsIn(0, nonce, 0, 0, AgentCommitType.IndexTypes, 1).single().commit).content,
                )
                runtime.submit(0, listOf(ContentItem.InputText("New message")))
                val sent = withTimeout(10.seconds) { request.await() }
                withTimeout(10.seconds) { runtime.getRunningTurnFlow(0).first { !it } }
                assertNotNull(sent.clientMetadata?.threadId)
                assertEquals(sent.clientMetadata?.threadId, sent.promptCacheKey)
                val next = runtime.getLastCommitsIn(
                    0,
                    runtime.getCacheNonce(0),
                    1,
                    runtime.getLatestIndex(0),
                    setOf(AgentCommitType.UserMessage),
                    1,
                ).single().index
                openSqliteConnection(Path(home, "kodex.db"), readOnly = true).use { db ->
                    assertTrue((db.query("""SELECT payload_json FROM agent_commits WHERE agent_id = 0 AND type = 'user_message' AND "index" = ?""",
                        listOf(next.toLong())).single().single() as String).contains("New message"))
                }
                val mirror = Path(home, "sessions", "0", "index", "$next.json")
                withTimeout(10.seconds) {
                    while (SystemCoroutineFileSystem.metadataOrNull(mirror) == null ||
                        !SystemCoroutineFileSystem.readString(mirror).contains("New message")) delay(10)
                    assertTrue(SystemCoroutineFileSystem.readString(mirror).contains("New message"))
                }
            }
        }
    }

    val legacyHomes = listOf("0.7.5" to true, "0.7.4" to true, "0.5.2" to true, null to true, "0.5.2" to false)
    for ((oldVersion, hasDatabase) in legacyHomes) {
        test("backend entries reject unsupported legacy data without changing it (marker=$oldVersion, sqlite=$hasDatabase)") {
            val fs = SystemCoroutineFileSystem
            suspend fun snapshot(directory: Path): Map<Path, ByteArray?> = buildMap {
                for (path in fs.list(directory)) {
                    if (fs.metadataOrNull(path)?.isDirectory == true) {
                        put(path, null)
                        putAll(snapshot(path))
                    } else {
                        put(path, fs.readBytes(path))
                    }
                }
            }

            val home = Path(SystemTemporaryDirectory, "kodex-legacy-catalog-host-${Random.nextLong().toULong()}")
            fs.createDirectories(home, mustCreate = true)
            try {
                if (oldVersion != null) fs.writeString(Path(home, "version.json"), "\"$oldVersion\"")
                fs.writeString(Path(home, "settings.yml"), "private fixture")
                if (hasDatabase) {
                    openSqliteConnection(Path(home, "kodex.db")).use { db ->
                        db.execute("CREATE TABLE sessions(session_id INTEGER PRIMARY KEY, archived INTEGER)")
                        db.execute("INSERT INTO sessions VALUES(1, 0)")
                        db.execute("PRAGMA user_version=1")
                    }
                } else {
                    val session = Path(home, "sessions", "0")
                    for (timeline in listOf("index", "work", "settings", "timestamp", "token-count", "unstable")) {
                        val directory = Path(session, timeline)
                        fs.createDirectories(directory)
                        fs.writeString(Path(directory, "latest.json"), if (timeline in listOf("index", "settings")) "0" else "-1")
                    }
                    fs.writeString(Path(session, "settings", "0.json"), """{"model":"fixture","threadName":"Legacy file session"}""")
                    fs.writeString(Path(session, "index", "0.json"), """{"type":"user_message","content":[{"type":"input_text","text":"From 0.5.2 file"}]}""")
                }
                fun assertUnchanged(before: Map<Path, ByteArray?>, after: Map<Path, ByteArray?>) {
                    assertEquals(before.keys, after.keys)
                    for ((path, bytes) in before) assertContentEquals(bytes, after.getValue(path), path.toString())
                }
                val before = snapshot(home)
                assertFailsWith<KodexHomeVersionException> { ensureBackend(home) }
                assertFailsWith<KodexHomeVersionException> { connectBackend(home, 18080) }
                assertFailsWith<KodexHomeVersionException> { discoverReadyBackend(home) }
                assertNull(BackendInstance.discover(home))
                assertUnchanged(before, snapshot(home))

                val startupFailure = assertFailsWith<KodexHomeVersionException> {
                    withTimeout(10.seconds) {
                        runBackendHost(home, Path(home, "codex"), Path(home, "agents"),
                            createClient = { error("Rejected Home must not open services") })
                    }
                }
                assertNull(BackendInstance.discover(home))
                val after = snapshot(home)
                if (oldVersion == "0.7.5" && hasDatabase) {
                    // The legacy checkpoint and header admit a preparation attempt. The
                    // owned snapshot rejects this incomplete schema before publication.
                    assertContains(startupFailure.message.orEmpty(), "Incomplete or unexpected recent Kodex SQLite schema")
                    val directories = setOf(Path(home, ".locks"), Path(home, ".locks", "home"), Path(home, ".locks", "backend"))
                    val lock = Path(home, ".locks", "backend", "instance.lock")
                    assertEquals(directories + lock, after.keys - before.keys)
                    for (directory in directories) assertEquals(null, after.getValue(directory))
                    assertContentEquals(byteArrayOf(), after.getValue(lock))
                    assertUnchanged(before, after - (directories + lock))
                } else {
                    assertUnchanged(before, after)
                }
            } finally {
                withContext(NonCancellable) { removeHost(home) }
            }
        }
    }
}
