package io.github.stream29.kodex.agentstorage.contract.ext

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.IndexedAgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableContextCompaction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWebSearchCall
import io.github.stream29.kodex.agentstorage.contract.KodexAgentStorage
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ResponseItem
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun storage(): InMemoryKodexAgentStorage =
    InMemoryKodexAgentStorage(
        KodexAgentSettings(
            model = OpenAiModelId("test-model"),
        ),
    )

private fun user(text: String): StableUserMessage =
    StableUserMessage(listOf(ContentItem.InputText(text)))

private fun assistant(text: String): StableAssistantMessage =
    StableAssistantMessage(listOf(ContentItem.OutputText(text)))

private fun work(status: String): StableWebSearchCall =
    StableWebSearchCall(ResponseItem.WebSearchCall(status = status))

private class CountingStorage(
    private val delegate: KodexAgentStorage,
) : KodexAgentStorage by delegate {
    var totalFetchedCommits: Int = 0

    override suspend fun getFirstCommitsIn(range: IntRange, types: Set<AgentCommitType>, limit: Int): List<IndexedAgentCommit> =
        delegate.getFirstCommitsIn(range, types, limit).also { totalFetchedCommits += it.size }

    override suspend fun getLastCommitsIn(range: IntRange, types: Set<AgentCommitType>, limit: Int): List<IndexedAgentCommit> =
        delegate.getLastCommitsIn(range, types, limit).also { totalFetchedCommits += it.size }
}

val agentStorageContractExtTest by testSuite {
    test("getFirstCommitsIn and getLastCommitsIn return ascending commits within limit") {
        val mutable = storage()
        val u1 = user("one")
        val a2 = assistant("two")
        val u3 = user("three")
        mutable.commit(u1) // 1
        mutable.commit(a2) // 2
        mutable.commit(u3) // 3

        assertEquals(
            listOf(IndexedAgentCommit(1, u1), IndexedAgentCommit(2, a2)),
            mutable.getFirstCommitsIn(0..3, AgentCommitType.IndexTypes, limit = 2),
        )
        assertEquals(
            listOf(IndexedAgentCommit(2, a2), IndexedAgentCommit(3, u3)),
            mutable.getLastCommitsIn(0..3, AgentCommitType.IndexTypes, limit = 2),
        )
    }

    test("compaction prefix stops fetching after its retained token budget and skips assistant messages") {
        val mutable = storage()
        repeat(500) {
            mutable.commit(user("x".repeat(1_000)))
            mutable.commit(assistant("y".repeat(1_000)))
        }
        val counted = CountingStorage(mutable)

        val prefix = counted.buildCompactionPrefix(beforeIndex = mutable.latestIndex() + 1)

        assertTrue(prefix.isNotEmpty())
        assertTrue(prefix.size < 500)
        assertTrue(counted.totalFetchedCommits < 500)
    }

    test("active message window retains prefix and preserves post-point events") {
        val mutable = storage()
        mutable.commit(user("retained")) // 1
        mutable.commit(StableContextCompaction(encryptedContent = "encrypted")) // 2
        mutable.commit(user("current")) // 3
        mutable.commit(work("current-work")) // 4

        val window = mutable.activeMessageWindowAt(4)

        assertEquals(
            listOf(
                StableUserMessage::class,
                StableContextCompaction::class,
                StableUserMessage::class,
                StableWebSearchCall::class,
            ),
            window.map { it::class },
        )
    }

    test("active message window keeps the whole history when no point exists") {
        val mutable = InMemoryKodexAgentStorage.empty()
        mutable.commit(user("first")) // 0
        mutable.commit(work("second")) // 1

        val window = mutable.activeMessageWindowAt(1)

        assertEquals(
            listOf(StableUserMessage::class, StableWebSearchCall::class),
            window.map { it::class },
        )
    }
}
