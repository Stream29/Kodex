package io.github.stream29.kodex.backend.host

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.history.contract.AgentHistoryLoadState
import io.github.stream29.kodex.cli.rpc.RpcServices
import io.github.stream29.kodex.cli.rpc.RpcSessionViews
import io.github.stream29.kodex.rpc.client.RestoringRpcClient
import io.github.stream29.kodex.rpc.local.withLocalRpcClient
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

val backendHistoryStartupTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("nonempty history reaches its full initial window over a real native connection promptly") {
        withHost { home, endpoint, _ ->
            // Before repository acquisition: a private persisted fixture, never a live owner's files.
            val session = Path(home, "sessions", "0")
            val fs = SystemCoroutineFileSystem
            listOf("index", "work", "settings", "timestamp", "token-count", "unstable").forEach { name ->
                val timeline = Path(session, name)
                fs.createDirectories(timeline)
                fs.writeString(Path(timeline, "latest.json"), "-1")
            }
            fs.writeString(Path(session, "settings", "0.json"),
                """{"model":"fixture","threadName":"Synthetic history"}""")
            fs.writeString(Path(session, "settings", "latest.json"), "0")
            repeat(240) { index ->
                fs.writeString(Path(session, "index", "$index.json"),
                    """{"type":"user_message","content":[{"type":"input_text","text":"Fixture $index"}]}""")
            }
            fs.writeString(Path(session, "index", "latest.json"), "239")
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
}
