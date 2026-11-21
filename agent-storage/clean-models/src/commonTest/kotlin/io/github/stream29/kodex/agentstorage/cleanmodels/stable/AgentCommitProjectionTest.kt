package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ResponseItem
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

val agentCommitProjectionTest by testSuite {
    test("root projection rejects metadata and runtime snapshots") {
        val records: List<AgentCommit> = listOf(
            KodexAgentSettings(OpenAiModelId("fixture")),
            LlmRequestMeta.Response(totalTokens = 5L, elapsedNanoseconds = 1_000_000L, outputTokens = 0),
            UnstableSnapshot(emptyList()),
            SteerSnapshot(emptyList()),
            AskUserSnapshot(emptyList()),
        )
        for (record in records) {
            val failure = assertFailsWith<IllegalArgumentException> { record.toResponseHistoryItems() }
            assertTrue(failure.message.orEmpty().contains(record.commitType.wireName))
        }
    }

    test("root projection keeps a checkpoint's ordered retained prefix") {
        val first = ContentItem.InputText("one")
        val second = ContentItem.InputText("two")
        val commit: AgentCommit = StableContextCompaction(
            encryptedContent = "fixture",
            retainedItems = listOf(StableUserMessage(listOf(first)), StableUserMessage(listOf(second))),
        )
        assertEquals(
            listOf(
                ResponseItem.Message(role = MessageRole.User, content = listOf(first)),
                ResponseItem.Message(role = MessageRole.User, content = listOf(second)),
                ResponseItem.Compaction(encryptedContent = "fixture"),
            ),
            commit.toResponseHistoryItems(),
        )
    }
}
