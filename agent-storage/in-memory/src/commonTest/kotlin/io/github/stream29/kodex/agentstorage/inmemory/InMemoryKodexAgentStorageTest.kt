package io.github.stream29.kodex.agentstorage.inmemory

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.CleanCompactionPoint
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableContextCompaction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableWebSearchCall
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCustomToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.agentstorage.contract.appendCompaction
import io.github.stream29.kodex.agentstorage.contract.ceilToIndex
import io.github.stream29.kodex.agentstorage.contract.floorToIndex
import io.github.stream29.kodex.agentstorage.contract.indexes
import io.github.stream29.kodex.agentstorage.contract.indexesDescending
import io.github.stream29.kodex.agentstorage.contract.latestIndex
import io.github.stream29.kodex.agentstorage.contract.latestValue
import io.github.stream29.kodex.agentstorage.contract.nextIndex
import io.github.stream29.kodex.agentstorage.contract.prevIndex
import io.github.stream29.kodex.agentstorage.contract.revert
import io.github.stream29.kodex.agentstorage.contract.revertWithTransaction
import io.github.stream29.kodex.agentstorage.contract.setWithTransaction
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ResponseItem
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertIs
import kotlin.time.Instant

private fun storage(
    initialSettings: KodexAgentSettings = settings("initial-model"),
): InMemoryKodexAgentStorage =
    InMemoryKodexAgentStorage(initialSettings)

private fun settings(model: String): KodexAgentSettings =
    KodexAgentSettings(
        model = OpenAiModelId(model),
        turnId = "turn-$model",
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
    test("construction publishes a clean initial snapshot with stable identity") {
        val first = storage()
        val second = storage()

        assertEquals(first.id, first.id)
        assertNotEquals(first.id, second.id)
        assertEquals(0, first.latestIndex())
        assertEquals(settings("initial-model"), first.settings.latestValue())
        assertEquals(0L, assertIs<CleanCompactionPoint>(first.index[0]).windowNumber)
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
        assertEquals(second, storage.work[3])
        assertEquals(null, storage.work.floorToIndex(0))
        assertEquals(1, storage.work.floorToIndex(2))
        assertEquals(3, storage.work.ceilToIndex(2))
        assertEquals(3, storage.work.nextIndex(1))
        assertEquals(1, storage.work.prevIndex(3))
        assertEquals(listOf(1, 3), storage.work.indexes().toList())
        assertEquals(listOf(3, 1), storage.work.indexesDescending(3).toList())
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

        assertEquals(listOf(1), storage.work.indexes().toList())
        assertEquals(first, storage.work[8])
        storage.work[3] = workEvent("replacement")
        assertEquals(listOf(1, 3), storage.work.indexes().toList())
    }

    test("set transaction compensates its own and nested timeline entries") {
        val storage = storage()
        storage.work[1] = workEvent("initial")

        assertFailsWith<IllegalStateException> {
            storage.work.setWithTransaction(2, workEvent("temporary")) {
                storage.timestamp.setWithTransaction(2, timestamp(2)) {
                    error("boom")
                }
            }
        }

        assertEquals(listOf(1), storage.work.indexes().toList())
        assertEquals(-1, storage.timestamp.latestIndex())
    }

    test("set transaction compensates cancellation") {
        val storage = storage()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            storage.work.setWithTransaction(1, workEvent("temporary")) {
                storage.timestamp.setWithTransaction(1, timestamp(1)) {
                    awaitCancellation()
                }
            }
        }

        job.cancelAndJoin()

        assertEquals(-1, storage.work.latestIndex())
        assertEquals(-1, storage.timestamp.latestIndex())
    }

    test("revert transaction restores the original suffix on failure") {
        val storage = storage()
        val first = workEvent("first")
        val third = workEvent("third")
        storage.work[1] = first
        storage.work[3] = third

        assertFailsWith<IllegalStateException> {
            storage.work.revertWithTransaction(2) {
                storage.work[2] = workEvent("replacement")
                error("boom")
            }
        }

        assertEquals(listOf(1, 3), storage.work.indexes().toList())
        assertEquals(first, storage.work[1])
        assertEquals(third, storage.work[3])
    }

    test("storage revert removes every clean timeline suffix") {
        val storage = storage()
        storage.index[1] = userMessage("first")
        storage.work[2] = workEvent("work")
        storage.unstable[3] = listOf(pendingTool("call"))
        storage.timestamp[4] = timestamp(3)
        storage.tokenCount[5] = 40L
        storage.settings[6] = settings("later")

        storage.revert(3)

        assertEquals(listOf(0, 1), storage.index.indexes().toList())
        assertEquals(listOf(2), storage.work.indexes().toList())
        assertEquals(-1, storage.unstable.latestIndex())
        assertEquals(-1, storage.timestamp.latestIndex())
        assertEquals(-1, storage.tokenCount.latestIndex())
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
        assertEquals(6, storage.nextIndex(4))
        assertEquals(4, storage.prevIndex(6))
    }

    test("append compaction stores consecutive point and output indexes") {
        val storage = storage()
        val previous = assertIs<CleanCompactionPoint>(storage.index[0])
        val output = StableContextCompaction(encryptedContent = "encrypted")
        val nextSettings = settings("next")
        storage.tokenCount[0] = 99L

        val outputIndex = storage.appendCompaction(
            output = output,
            timestamp = timestamp(10),
            previousPoint = previous,
            nextWindowId = "window-1",
            settings = nextSettings,
        )
        val pointIndex = outputIndex - 1

        assertEquals(2, outputIndex)
        assertEquals(
            CleanCompactionPoint(
                windowNumber = 1,
                firstWindowId = previous.firstWindowId,
                previousWindowId = previous.windowId,
                windowId = "window-1",
            ),
            storage.index[pointIndex],
        )
        assertEquals(output, storage.work[outputIndex])
        assertEquals(nextSettings, storage.settings[pointIndex])
        assertEquals(0L, storage.tokenCount[pointIndex])
        assertEquals(pointIndex, storage.tokenCount.latestIndex())
        assertEquals(timestamp(10), storage.timestamp[outputIndex])
        assertEquals(outputIndex, storage.latestIndex())
    }
}
