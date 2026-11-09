@file:Suppress("DEPRECATION")

package io.github.stream29.kodex.agentstorage.inmemory

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CommitTimestamp
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.IndexedAgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableContextCompaction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWebSearchCall
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.TokenCountSnapshot
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.UnstableSnapshot
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCustomToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.agentstorage.contract.ext.appendCompaction
import io.github.stream29.kodex.agentstorage.contract.getCommitAtOrAfter
import io.github.stream29.kodex.agentstorage.contract.getCommitAtOrBefore
import io.github.stream29.kodex.agentstorage.contract.getCommitExact
import io.github.stream29.kodex.agentstorage.contract.getCommitsIn
import io.github.stream29.kodex.agentstorage.contract.getSettingsAt
import io.github.stream29.kodex.agentstorage.contract.getLlmRequestMetaAtOrNull
import io.github.stream29.kodex.agentstorage.contract.getUnstableAtOrNull
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ResponseItem
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private fun storage(
    initialSettings: KodexAgentSettings = settings("initial-model"),
): InMemoryKodexAgentStorage =
    InMemoryKodexAgentStorage(initialSettings)

private fun settings(model: String): KodexAgentSettings =
    KodexAgentSettings(
        model = OpenAiModelId(model),
    )

private fun timestamp(seconds: Long): Instant =
    Instant.fromEpochSeconds(seconds)

private fun userMessage(text: String): StableUserMessage =
    StableUserMessage(listOf(ContentItem.InputText(text)))

private fun assistantMessage(text: String): StableAssistantMessage =
    StableAssistantMessage(listOf(ContentItem.OutputText(text)))

private fun workEvent(status: String): StableWebSearchCall =
    StableWebSearchCall(ResponseItem.WebSearchCall(status = status))

private fun pendingTool(callId: String): PendingToolEvent =
    PendingCustomToolEvent(
        callId = callId,
        name = "tool-$callId",
        input = "input-$callId",
    )

val inMemoryKodexAgentStorageTest by testSuite {
    test("construction publishes an initial snapshot without a compaction point") {
        val first = storage()
        val second = storage()

        assertTrue(first.uri.startsWith("memory:"))
        assertTrue(second.uri.startsWith("memory:"))
        assertEquals(first.uri, first.uri)
        assertNotEquals(first.uri, second.uri)
        assertEquals(0, first.latestIndex())
        assertEquals(OpenAiModelId("initial-model"), first.getSettingsAt(0).model)
        assertTrue(first.getSettingsAt(0).turnId.isNotEmpty())
        assertNull(first.getCommitAtOrBefore(0, AgentCommitType.IndexTypes))
        assertNull(first.getCommitExact(0, AgentCommitType.IndexTypes))
        assertEquals(0L, first.getSettingsAt(0).windowNumber)
        assertNull(first.getCommitAtOrBefore(0, AgentCommitType.WorkTypes))
        assertNull(first.getCommitAtOrBefore(0, AgentCommitType.UnstableTypes))
        assertEquals(0, first.countCommitsIn(0..0, AgentCommitType.IndexTypes))
        assertEquals(1, first.countCommitsIn(0..0, AgentCommitType.SettingsTypes))
    }

    test("work commits are sparse and reject duplicate family in a single batch") {
        val storage = storage()
        val first = workEvent("first")
        val second = workEvent("second")

        val idx1 = storage.commit(first)
        val idx2 = storage.commit(CommitTimestamp(timestamp(2)))
        val idx3 = storage.commit(second)

        assertEquals(1, idx1)
        assertEquals(2, idx2)
        assertEquals(3, idx3)
        assertEquals(3, storage.getCommitAtOrBefore(3, AgentCommitType.WorkTypes)?.index)
        assertEquals(first, storage.getCommitAtOrBefore(2, AgentCommitType.WorkTypes)?.commit)
        assertEquals(first, storage.getCommitExact(1, AgentCommitType.WorkTypes)?.commit)
        assertNull(storage.getCommitExact(2, AgentCommitType.WorkTypes))
        assertEquals(second, storage.getCommitAtOrBefore(3, AgentCommitType.WorkTypes)?.commit)
        assertNull(storage.getCommitAtOrBefore(0, AgentCommitType.WorkTypes))
        assertEquals(1, storage.getCommitAtOrBefore(2, AgentCommitType.WorkTypes)?.index)
        assertEquals(3, storage.getCommitAtOrAfter(2, AgentCommitType.WorkTypes)?.index)
        assertEquals(2, storage.countCommitsIn(0..3, AgentCommitType.WorkTypes))
        assertEquals(
            listOf(IndexedAgentCommit(1, first)),
            storage.getCommitsIn(0..2, AgentCommitType.WorkTypes),
        )
        assertEquals(
            listOf(IndexedAgentCommit(1, first), IndexedAgentCommit(3, second)),
            storage.getCommitsIn(0..Int.MAX_VALUE, AgentCommitType.WorkTypes),
        )
        assertFailsWith<IllegalArgumentException> {
            storage.commit(listOf(first, second))
        }
    }

    test("unstable snapshots preserve pending order and allow out-of-order completion") {
        val storage = storage()
        val first = pendingTool("first")
        val second = pendingTool("second")

        storage.commit(UnstableSnapshot(listOf(first)))
        storage.commit(UnstableSnapshot(listOf(first, second)))
        storage.commit(listOf(assistantMessage("second completed"), UnstableSnapshot(listOf(first))))
        storage.commit(listOf(assistantMessage("first completed"), UnstableSnapshot(emptyList())))

        assertEquals(listOf(first, second), storage.getUnstableAtOrNull(2))
        assertEquals(listOf(first), storage.getUnstableAtOrNull(3))
        assertEquals(emptyList(), storage.getUnstableAtOrNull(4))
    }

    test("revert removes suffix and permits replacement") {
        val storage = storage()
        val first = workEvent("first")

        storage.commit(first) // 1
        storage.commit(CommitTimestamp(timestamp(2))) // 2
        storage.commit(workEvent("third")) // 3
        storage.revert(3)

        assertEquals(
            listOf(1),
            storage.getCommitsIn(0..Int.MAX_VALUE, AgentCommitType.WorkTypes).map { it.index },
        )
        assertEquals(first, storage.getCommitAtOrBefore(8, AgentCommitType.WorkTypes)?.commit)
        val replacement = workEvent("replacement")
        storage.commit(replacement) // 3
        assertEquals(
            listOf(1, 3),
            storage.getCommitsIn(0..Int.MAX_VALUE, AgentCommitType.WorkTypes).map { it.index },
        )
    }

    test("storage revert removes every commit family at or after boundary") {
        val storage = storage()
        storage.commit(CommitTimestamp(timestamp(1))) // 1
        storage.commit(listOf(userMessage("first"), workEvent("work"))) // 2
        storage.commit(UnstableSnapshot(listOf(pendingTool("call")))) // 3
        storage.commit(CommitTimestamp(timestamp(3))) // 4
        storage.commit(TokenCountSnapshot.Response(40L)) // 5
        storage.commit(settings("later")) // 6

        storage.revert(3)

        assertEquals(2, storage.latestIndex())
        assertEquals(listOf(2), storage.getCommitsIn(0..Int.MAX_VALUE, AgentCommitType.IndexTypes).map { it.index })
        assertEquals(listOf(2), storage.getCommitsIn(0..Int.MAX_VALUE, AgentCommitType.WorkTypes).map { it.index })
        assertNull(storage.getCommitAtOrBefore(2, AgentCommitType.UnstableTypes))
        assertEquals(1, storage.getCommitAtOrBefore(2, AgentCommitType.TimestampTypes)?.index)
        assertEquals(0, storage.getCommitAtOrBefore(2, AgentCommitType.TokenCountTypes)?.index)
        assertEquals(0, storage.getCommitAtOrBefore(2, AgentCommitType.SettingsTypes)?.index)
    }

    test("append compaction stores output and settings at a single atomic index") {
        val storage = storage()
        val previousSettings = storage.getSettingsAt(0)
        val output = StableContextCompaction(encryptedContent = "encrypted")
        storage.commit(TokenCountSnapshot.Response(99L)) // 1

        val compactionIndex = storage.appendCompaction(
            output = output,
            timestamp = timestamp(10),
            nextWindowId = "window-1",
            previousSettings = previousSettings,
        )

        assertEquals(2, compactionIndex)
        assertEquals(output, storage.getCommitExact(compactionIndex, AgentCommitType.IndexTypes)?.commit)
        assertEquals(
            previousSettings.copy(
                windowNumber = 1,
                previousWindowId = previousSettings.windowId,
                windowId = "window-1",
            ),
            storage.getSettingsAt(compactionIndex),
        )
        assertEquals(0L, storage.getLlmRequestMetaAtOrNull(compactionIndex)?.totalTokens)
        assertEquals(compactionIndex, storage.getCommitAtOrBefore(compactionIndex, AgentCommitType.TokenCountTypes)?.index)
        assertEquals(timestamp(10), storage.getCommitExact(compactionIndex)?.createdAt)
        assertEquals(compactionIndex, storage.latestIndex())
    }
}
