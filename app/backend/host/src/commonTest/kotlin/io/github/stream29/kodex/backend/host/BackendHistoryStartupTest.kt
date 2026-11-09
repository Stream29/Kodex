package io.github.stream29.kodex.backend.host

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.sqlite.ensureSqlitePhaseOneSchema
import io.github.stream29.kodex.app.history.contract.AgentHistoryLoadState
import io.github.stream29.kodex.app.migration.CurrentKodexApplicationVersion
import io.github.stream29.kodex.cli.rpc.RpcServices
import io.github.stream29.kodex.cli.rpc.RpcSessionViews
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.ResponsesApiRequest
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.rpc.client.RestoringRpcClient
import io.github.stream29.kodex.rpc.contract.AgentRuntimeRpc
import io.github.stream29.kodex.rpc.contract.AgentStorageRpc
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import io.github.stream29.kodex.rpc.local.withLocalRpcClient
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.sqlite.openSqliteConnection
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.rpc.withService

val backendHistoryStartupTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("nonempty history reaches its full initial window over a real native connection promptly") {
        withHost { home, endpoint, _ ->
            openSqliteConnection(Path(home, "kodex.db")).use { db ->
                ensureSqlitePhaseOneSchema(db)
                db.transaction {
                    db.execute("INSERT INTO sessions(session_id) VALUES (0)")
                    db.execute("INSERT INTO agents(agent_id, session_id) VALUES (0, 0)")
                    db.execute("INSERT INTO agent_commits VALUES (0, 'settings', 0, ?, '2026-01-01T00:00:00Z')",
                        listOf("""{"model":"fixture","threadName":"Synthetic history"}"""))
                    repeat(240) { index ->
                        db.execute("INSERT INTO agent_commits VALUES (0, 'user_message', ?, ?, '2026-01-01T00:00:00Z')", listOf(index.toLong(),
                            """{"type":"user_message","content":[{"type":"input_text","text":"Fixture $index"}]}"""))
                    }
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
                        val agent = requireNotNull(view.agent.value)
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

    test("a migrated Home serves SQLite history and mirrors new messages") {
        val request = CompletableDeferred<ResponsesApiRequest>()
        withHost(prepareHome = { home ->
            val fs = SystemCoroutineFileSystem
            val session = Path(home, "sessions", "0")
            for (name in listOf("index", "work", "settings", "timestamp", "token-count", "unstable")) {
                fs.createDirectories(Path(session, name))
                fs.writeString(Path(session, name, "latest.json"), "-1")
            }
            openSqliteConnection(Path(home, "kodex.db")).use { db ->
                ensureSqlitePhaseOneSchema(db)
                db.transaction {
                    db.execute("INSERT INTO sessions(session_id, archived) VALUES (0, 0)")
                    db.execute("INSERT INTO agents(agent_id, session_id, parent_agent_id) VALUES (0, 0, NULL)")
                    db.execute(
                        "INSERT INTO agent_commits VALUES (0, 'settings', 0, ?, '2026-01-01T00:00:00Z')",
                        listOf("""{"model":"fixture","threadName":"Migrated session"}"""),
                    )
                    db.execute(
                        "INSERT INTO agent_commits VALUES (0, 'user_message', 0, ?, '2026-01-01T00:00:00Z')",
                        listOf("""{"type":"user_message","content":[{"type":"input_text","text":"Migrated history"}]}"""),
                    )
                }
            }
            // Startup and RPC history reads must not use the retained mirror.
            fs.writeString(Path(session, "index", "0.json"), "corrupted mirror")
            fs.writeString(Path(home, "version.json"), "\"$CurrentKodexApplicationVersion\"")
        }, stream = {
            flow { emit(ResponsesStreamEvent.Completed(Response(id = "r", endTurn = true))) }
        }, observeRequest = { request.complete(it) }) { home, endpoint, _ ->
            withLocalRpcClient(home, endpoint) { connection ->
                val client = RestoringRpcClient(connection)
                val global = client.withService<GlobalRpc>()
                val runtime = client.withService<AgentRuntimeRpc>()
                val storage = client.withService<AgentStorageRpc>()
                assertEquals("Migrated session", global.getSessionCatalog(false).single().threadName)
                global.keepSessionAlive(0)
                val nonce = storage.getCacheNonce(0)
                assertEquals(
                    "Migrated session",
                    (storage.getLastCommitsIn(0, nonce, 0, 0, AgentCommitType.SettingsTypes, 1).single().commit as KodexAgentSettings).threadName,
                )
                assertEquals(
                    listOf(ContentItem.InputText("Migrated history")),
                    assertIs<StableUserMessage>(storage.getLastCommitsIn(0, nonce, 0, 0, AgentCommitType.IndexTypes, 1).single().commit).content,
                )
                val next = runtime.appendUserMessage(0, listOf(ContentItem.InputText("After migration")))
                runtime.resume(0)
                val sent = withTimeout(10.seconds) { request.await() }
                assertNotNull(sent.clientMetadata?.threadId)
                assertEquals(sent.clientMetadata?.threadId, sent.promptCacheKey)
                openSqliteConnection(Path(home, "kodex.db"), readOnly = true).use { db ->
                    assertTrue((db.query("""SELECT payload_json FROM agent_commits WHERE agent_id = 0 AND type = 'user_message' AND "index" = ?""",
                        listOf(next.toLong())).single().single() as String).contains("After migration"))
                }
                val mirror = Path(home, "sessions", "0", "index", "$next.json")
                withTimeout(10.seconds) {
                    while (SystemCoroutineFileSystem.metadataOrNull(mirror) == null ||
                        !SystemCoroutineFileSystem.readString(mirror).contains("After migration")) delay(10)
                    assertTrue(SystemCoroutineFileSystem.readString(mirror).contains("After migration"))
                }
            }
        }
    }

    test("startup automatically upgrades a 0.5.2 Home to 0.7.0 SQLite before serving RPC") {
        withHost(prepareHome = { home ->
            val fs = SystemCoroutineFileSystem
            val session = Path(home, "sessions", "0")
            for (name in listOf("index", "work", "settings", "timestamp", "token-count", "unstable")) {
                fs.createDirectories(Path(session, name))
                fs.writeString(Path(session, name, "latest.json"), "-1")
            }
            fs.writeString(Path(session, "settings", "0.json"), """{"model":"fixture","threadName":"Auto-migrated session"}""")
            fs.writeString(Path(session, "settings", "latest.json"), "0")
            fs.writeString(Path(session, "index", "0.json"), """{"type":"user_message","content":[{"type":"input_text","text":"From 0.5.2 file"}]}""")
            fs.writeString(Path(session, "index", "latest.json"), "0")
            fs.writeString(Path(home, "version.json"), "\"0.5.2\"")
        }) { home, endpoint, _ ->
            assertEquals("\"0.7.0\"", SystemCoroutineFileSystem.readString(Path(home, "version.json")))
            assertTrue(SystemCoroutineFileSystem.exists(Path(home, "kodex.db")))
            withLocalRpcClient(home, endpoint) { connection ->
                val client = RestoringRpcClient(connection)
                val global = client.withService<GlobalRpc>()
                val storage = client.withService<AgentStorageRpc>()
                assertEquals("Auto-migrated session", global.getSessionCatalog(false).single().threadName)
                global.keepSessionAlive(0)
                val nonce = storage.getCacheNonce(0)
                assertEquals(
                    listOf(ContentItem.InputText("From 0.5.2 file")),
                    assertIs<StableUserMessage>(storage.getLastCommitsIn(0, nonce, 0, 0, AgentCommitType.IndexTypes, 1).single().commit).content,
                )
            }
        }
    }
}
