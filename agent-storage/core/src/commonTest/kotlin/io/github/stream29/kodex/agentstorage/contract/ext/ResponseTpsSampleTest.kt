package io.github.stream29.kodex.agentstorage.contract.ext

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.IndexedAgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.LlmRequestMeta
import io.github.stream29.kodex.agentstorage.contract.KodexAgentStorage
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import kotlin.test.assertEquals
import kotlin.test.assertNull

val responseTpsSampleTest by testSuite {
    test("only a response in this Agent's visible history is a sample") {
        val parent = InMemoryKodexAgentStorage.empty()
        val child = InMemoryKodexAgentStorage.empty()
        assertNull(parent.latestResponseTpsSampleAt(-1))
        parent.commit(LlmRequestMeta.Initialization())
        child.commit(LlmRequestMeta.Response(totalTokens = 200, elapsedNanoseconds = 5, outputTokens = 17))
        assertNull(parent.latestResponseTpsSampleAt(parent.latestIndex()))
        assertEquals(ResponseTpsSample(5, 17), child.latestResponseTpsSampleAt(child.latestIndex()))

        val older = parent.commit(LlmRequestMeta.Response(totalTokens = 200, elapsedNanoseconds = 15, outputTokens = 4))
        parent.commit(LlmRequestMeta.Compaction(totalTokens = 90))
        val latest = parent.commit(LlmRequestMeta.Response(totalTokens = 100, elapsedNanoseconds = 20, outputTokens = 0))
        parent.commit(LlmRequestMeta.Legacy(totalTokens = 101))
        assertEquals(ResponseTpsSample(15, 4), parent.latestResponseTpsSampleAt(older))
        assertEquals(ResponseTpsSample(20, 0), parent.latestResponseTpsSampleAt(parent.latestIndex()))
        parent.revert(latest)
        assertEquals(ResponseTpsSample(15, 4), parent.latestResponseTpsSampleAt(parent.latestIndex()))
    }

    test("lookup uses a single reverse range query limited to one response") {
        val storage = InMemoryKodexAgentStorage.empty()
        storage.commit(LlmRequestMeta.Response(totalTokens = 100, elapsedNanoseconds = 20, outputTokens = 3))
        var queries = 0
        val observed = object : KodexAgentStorage by storage {
            override suspend fun getLastCommitsIn(
                range: IntRange,
                types: Set<AgentCommitType>,
                limit: Int,
            ): List<IndexedAgentCommit> {
                queries++
                assertEquals(0..7, range)
                assertEquals(setOf(AgentCommitType.LlmRequestMetaResponse), types)
                assertEquals(1, limit)
                return storage.getLastCommitsIn(range, types, limit)
            }
        }
        assertEquals(ResponseTpsSample(20, 3), observed.latestResponseTpsSampleAt(7))
        assertEquals(1, queries)
    }
}
