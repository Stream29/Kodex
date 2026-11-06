package io.github.stream29.kodex.agentstorage.inmemory

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CleanCompactionPoint
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
import io.github.stream29.kodex.agentstorage.contract.ceilToIndex
import io.github.stream29.kodex.agentstorage.contract.ext.appendCompaction
import io.github.stream29.kodex.agentstorage.contract.floorToIndex
import io.github.stream29.kodex.agentstorage.contract.getIndexEntryExact
import io.github.stream29.kodex.agentstorage.contract.getSettingsAt
import io.github.stream29.kodex.agentstorage.contract.getTimestampExact
import io.github.stream29.kodex.agentstorage.contract.getTokenCountAt
import io.github.stream29.kodex.agentstorage.contract.getUnstableAt
import io.github.stream29.kodex.agentstorage.contract.getWorkEventAt
import io.github.stream29.kodex.agentstorage.contract.getWorkEventExact
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
        assertNull(first.floorToIndex(0, AgentCommitType.IndexTypes))
        assertNull(first.getIndexEntryExact(0))
        assertEquals(0L, first.getSettingsAt(0).windowNumber)
        assertNull(first.floorToIndex(0, AgentCommitType.WorkTypes))
        assertNull(first.floorToIndex(0, AgentCommitType.UnstableTypes))
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
        assertEquals(3, storage.floorToIndex(3, AgentCommitType.WorkTypes))
        assertEquals(first, storage.getWorkEventAt(2))
        assertEquals(first, storage.getWorkEventExact(1))
        assertNull(storage.getWorkEventExact(2))
        assertEquals(second, storage.getWorkEventAt(3))
        assertNull(storage.floorToIndex(0, AgentCommitType.WorkTypes))
        assertEquals(1, storage.floorToIndex(2, AgentCommitType.WorkTypes))
        assertEquals(3, storage.ceilToIndex(2, AgentCommitType.WorkTypes))
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

        assertEquals(listOf(first, second), storage.getUnstableAt(2))
        assertEquals(listOf(first), storage.getUnstableAt(3))
        assertEquals(emptyList(), storage.getUnstableAt(4))
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
        assertEquals(first, storage.getWorkEventAt(8))
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
        assertNull(storage.floorToIndex(2, AgentCommitType.UnstableTypes))
        assertEquals(1, storage.floorToIndex(2, AgentCommitType.TimestampTypes))
        assertEquals(0, storage.floorToIndex(2, AgentCommitType.TokenCountTypes))
        assertEquals(0, storage.floorToIndex(2, AgentCommitType.SettingsTypes))
    }

    test("append compaction stores consecutive point and output indexes") {
        val storage = storage()
        val previousSettings = storage.getSettingsAt(0)
        val output = StableContextCompaction(encryptedContent = "encrypted")
        storage.commit(TokenCountSnapshot.Response(99L)) // 1

        val outputIndex = storage.appendCompaction(
            output = output,
            timestamp = timestamp(10),
            nextWindowId = "window-1",
            previousSettings = previousSettings,
        )
        val pointIndex = outputIndex - 1

        assertEquals(3, outputIndex)
        assertIs<CleanCompactionPoint>(storage.getIndexEntryExact(pointIndex))
        assertEquals(output, storage.getWorkEventExact(outputIndex))
        assertEquals(
            previousSettings.copy(
                windowNumber = 1,
                previousWindowId = previousSettings.windowId,
                windowId = "window-1",
            ),
            storage.getSettingsAt(pointIndex),
        )
        assertEquals(0L, storage.getTokenCountAt(pointIndex).totalTokens)
        assertEquals(pointIndex, storage.floorToIndex(outputIndex, AgentCommitType.TokenCountTypes))
        assertEquals(timestamp(10), storage.getTimestampExact(outputIndex))
        assertEquals(outputIndex, storage.latestIndex())
    }
}
