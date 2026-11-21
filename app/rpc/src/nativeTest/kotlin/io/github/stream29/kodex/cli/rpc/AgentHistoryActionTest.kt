package io.github.stream29.kodex.cli.rpc

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.rpc.models.AgentStateValue
import io.github.stream29.kodex.utils.rpcexception.CacheNonceMismatch
import kotlinx.coroutines.flow.first
import kotlin.test.*

val agentHistoryActionTest by testSuite {
    test("history fork is bound to the source Agent and rejects another Agent's nonce") {
        frontend {
            val first = create("first")
            val second = create("second")
            val agent = requireNotNull(first.rootAgent.value)
            assertFailsWith<CacheNonceMismatch> {
                agent.forkHistory(1, second.current().storage.cacheNonce.value)
            }
            val fork = agent.forkHistory(first.current().latestIndex.value + 1, first.current().storage.cacheNonce.value)
            assertEquals("[fork] first", views.open(fork).settings.value.name)
        }
    }
    test("revert changes cache identity and a stale fork is not retried") {
        frontend {
            val session = create("history")
            val binding = session.current()
            binding.submit(listOf(ContentItem.InputText("first")))
            binding.state.first { it == AgentStateValue.AssistantMessage }
            binding.running.first { !it }
            val nonce = binding.storage.cacheNonce.value
            val agent = requireNotNull(session.rootAgent.value)
            // Keep initialization, discard all later events.
            agent.revertHistory(1, nonce)
            binding.storage.cacheNonce.first { it != nonce }
            binding.state.first { it != AgentStateValue.ExternalWrite }
            assertFailsWith<CacheNonceMismatch> { agent.forkHistory(1, nonce) }
            assertEquals(1, services.global.getSessionCatalog(true).size)
        }
    }
}
