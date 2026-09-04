package io.github.stream29.kodex.agentstorage.inmemory

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.CleanCompactionPoint
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableContextCompaction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableWebSearchCall
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCustomToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.agentstorage.contract.ext.appendCompaction
import io.github.stream29.kodex.agentstorage.contract.ceilToIndex
import io.github.stream29.kodex.agentstorage.contract.floorToIndex
import io.github.stream29.kodex.agentstorage.contract.latestIndex
import io.github.stream29.kodex.agentstorage.contract.latestValue
import io.github.stream29.kodex.agentstorage.contract.revert
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ResponseItem
import kotlinx.coroutines.flow.toList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertIs
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
        assertEquals(OpenAiModelId("initial-model"), first.settings.latestValue().model)
        assertTrue(first.settings.latestValue().turnId.isNotEmpty())
        assertEquals(-1, first.index.latestIndex())
        assertEquals(null, first.index.getExact(0))
        assertEquals(0L, first.settings[0].windowNumber)
        assertEquals(-1, first.work.latestIndex())
        assertEquals(-1, first.unstable.latestIndex())
    }

    test("work timeline is sparse and rejects non-tail writes") {
        val storage = storage()
        val first = workEvent("first")
        val second = workEvent("second")

        storage.work[1] = first
        storage.work[3] = second

        assertEquals(3, storage.work.latestIndex())
        assertEquals(first, storage.work[2])
        assertEquals(first, storage.work.getExact(1))
        assertEquals(null, storage.work.getExact(2))
        assertEquals(second, storage.work[3])
        assertEquals(null, storage.work.floorToIndex(0))
        assertEquals(1, storage.work.floorToIndex(2))
        assertEquals(3, storage.work.ceilToIndex(2))
        assertEquals(listOf(1), storage.work.indexesIn(0..2))
        assertEquals(listOf(1, 3), storage.work.indexesIn(0..Int.MAX_VALUE))
        assertEquals(listOf(3, 1), storage.work.indexesIn(0..3).asReversed())
        assertFailsWith<IllegalArgumentException> {
            storage.work[2] = workEvent("overwrite")
        }
    }

    test("unstable snapshots preserve pending order and allow out-of-order completion") {
        val storage = storage()
        val first = pendingTool("first")
        val second = pendingTool("second")

        storage.unstable[1] = listOf(first)
        storage.unstable[2] = listOf(first, second)
        storage.index[3] = assistantMessage("second completed")
        storage.unstable[3] = listOf(first)
        storage.index[4] = assistantMessage("first completed")
        storage.unstable[4] = emptyList()

        assertEquals(listOf(first, second), storage.unstable[2])
        assertEquals(listOf(first), storage.unstable[3])
        assertEquals(emptyList(), storage.unstable[4])
    }

    test("timeline revert removes suffix and permits replacement") {
        val storage = storage()
        val first = workEvent("first")

        storage.work[1] = first
        storage.work[3] = workEvent("third")
        storage.work.revert(3)

        assertEquals(listOf(1), storage.work.indexesIn(0..Int.MAX_VALUE))
        assertEquals(first, storage.work[8])
        storage.work[3] = workEvent("replacement")
        assertEquals(listOf(1, 3), storage.work.indexesIn(0..Int.MAX_VALUE))
    }

    test("storage revert removes every clean timeline suffix") {
        val storage = storage()
        storage.index[2] = userMessage("first")
        storage.work[2] = workEvent("work")
        storage.unstable[3] = listOf(pendingTool("call"))
        storage.timestamp[4] = timestamp(3)
        storage.tokenCount[5] = 40L
        storage.settings[6] = settings("later")

        storage.revert(3)

        assertEquals(listOf(2), storage.index.indexesIn(0..Int.MAX_VALUE))
        assertEquals(listOf(2), storage.work.indexesIn(0..Int.MAX_VALUE))
        assertEquals(-1, storage.unstable.latestIndex())
        assertEquals(0, storage.timestamp.latestIndex())
        assertEquals(0, storage.tokenCount.latestIndex())
        assertEquals(0, storage.settings.latestIndex())
    }

    test("global index helpers merge the six timelines") {
        val storage = storage()
        storage.index[2] = userMessage("two")
        storage.work[3] = workEvent("three")
        storage.timestamp[4] = timestamp(4)
        storage.unstable[6] = listOf(pendingTool("six"))

        assertEquals(6, storage.latestIndex())
        assertEquals(4, storage.floorToIndex(5))
        assertEquals(6, storage.ceilToIndex(5))
    }

    test("append compaction stores consecutive point and output indexes") {
        val storage = storage()
        val previousSettings = storage.settings[0]
        val output = StableContextCompaction(encryptedContent = "encrypted")
        storage.tokenCount[1] = 99L

        val outputIndex = storage.appendCompaction(
            output = output,
            timestamp = timestamp(10),
            nextWindowId = "window-1",
            previousSettings = previousSettings,
        )
        val pointIndex = outputIndex - 1

        assertEquals(3, outputIndex)
        assertIs<CleanCompactionPoint>(storage.index[pointIndex])
        assertEquals(output, storage.work[outputIndex])
        assertEquals(
            previousSettings.copy(
                windowNumber = 1,
                previousWindowId = previousSettings.windowId,
                windowId = "window-1",
            ),
            storage.settings[pointIndex],
        )
        assertEquals(0L, storage.tokenCount[pointIndex])
        assertEquals(pointIndex, storage.tokenCount.latestIndex())
        assertEquals(timestamp(10), storage.timestamp[outputIndex])
        assertEquals(outputIndex, storage.latestIndex())
    }
}
