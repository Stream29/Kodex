package io.github.stream29.kodex.cli.session

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.test.*
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.rpc.models.AgentStateValue
import io.github.stream29.kodex.utils.rpcexception.CacheNonceMismatch
import kotlinx.coroutines.flow.first
import kotlin.test.*

val agentHistoryActionTest by testSuite {
    test("history fork rejects a foreign frontend owner") {
        withRpcFrontend {
            val first = create("first")
            val second = create("second")
            val agent = requireNotNull(first.rootAgent.value)
            assertFailsWith<IllegalArgumentException> {
                second.fork(agent, 1, views.open(first.sessionIndex).current().storage.index.cacheNonce.value)
            }
        }
    }
    test("revert changes cache identity and a stale fork is not retried") {
        withRpcFrontend {
            val session = create("history")
            val binding = views.open(session.sessionIndex).current()
            binding.appendUserMessage(listOf(ContentItem.InputText("first")))
            binding.resume()
            binding.state.first { it == AgentStateValue.AssistantMessage }
            val nonce = binding.storage.index.cacheNonce.value
            val agent = requireNotNull(session.rootAgent.value)
            // Keep initialization, discard all later events.
            agent.revertHistory(1, nonce)
            binding.storage.index.cacheNonce.first { it != nonce }
            assertFailsWith<CacheNonceMismatch> { session.fork(agent, 1, nonce) }
            assertEquals(1, services.global.getSessionCatalog(true).size)
        }
    }
}
