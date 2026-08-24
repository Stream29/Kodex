package io.github.stream29.kodex.agentstorage.inmemory

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.CleanCompactionCheckpoint
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCustomToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.agentstorage.contract.appendCompactionCheckpoint
import io.github.stream29.kodex.agentstorage.contract.ceilToIndex
import io.github.stream29.kodex.agentstorage.contract.floorToIndex
import io.github.stream29.kodex.agentstorage.contract.forkTo
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

private fun userMessage(text: String): StableCleanEvent.UserMessage =
    StableCleanEvent.UserMessage(listOf(ContentItem.InputText(text)))

private fun assistantMessage(text: String): StableCleanEvent.AssistantMessage =
    StableCleanEvent.AssistantMessage(listOf(ContentItem.OutputText(text)))

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
        assertEquals(emptyList(), first.compaction[0].prefix)
        assertEquals(null, first.compaction[0].compaction)
        assertEquals(0, first.compaction[0].historyBaseIndex)
        assertEquals(0L, first.compaction[0].windowNumber)
        assertEquals(-1, first.stable.latestIndex())
        assertEquals(-1, first.unstable.latestIndex())
    }

    test("stable timeline is sparse and rejects non-tail writes") {
        val storage = storage()
        val first = userMessage("first")
        val second = assistantMessage("second")

        storage.stable[1] = first
        storage.stable[3] = second

        assertEquals(3, storage.stable.latestIndex())
        assertEquals(first, storage.stable[2])
        assertEquals(second, storage.stable[3])
        assertEquals(null, storage.stable.floorToIndex(0))
        assertEquals(1, storage.stable.floorToIndex(2))
        assertEquals(3, storage.stable.ceilToIndex(2))
        assertEquals(3, storage.stable.nextIndex(1))
        assertEquals(1, storage.stable.prevIndex(3))
        assertEquals(listOf(1, 3), storage.stable.indexes().toList())
        assertEquals(listOf(3, 1), storage.stable.indexesDescending(3).toList())
        assertFailsWith<IllegalArgumentException> {
            storage.stable[2] = userMessage("overwrite")
        }
    }

    test("unstable snapshots preserve pending order and allow out-of-order completion") {
        val storage = storage()
        val first = pendingTool("first")
        val second = pendingTool("second")

        storage.unstable[1] = listOf(first)
        storage.unstable[2] = listOf(first, second)
        storage.stable[3] = assistantMessage("second completed")
        storage.unstable[3] = listOf(first)
        storage.stable[4] = assistantMessage("first completed")
        storage.unstable[4] = emptyList()

        assertEquals(listOf(first, second), storage.unstable[2])
        assertEquals(listOf(first), storage.unstable[3])
        assertEquals(emptyList(), storage.unstable[4])
    }

    test("timeline revert removes suffix and permits replacement") {
        val storage = storage()
        val first = userMessage("first")

        storage.stable[1] = first
        storage.stable[3] = assistantMessage("third")
        storage.stable.revert(3)

        assertEquals(listOf(1), storage.stable.indexes().toList())
        assertEquals(first, storage.stable[8])
        storage.stable[3] = assistantMessage("replacement")
        assertEquals(listOf(1, 3), storage.stable.indexes().toList())
    }

    test("set transaction compensates its own and nested timeline entries") {
        val storage = storage()
        storage.stable[1] = userMessage("initial")

        assertFailsWith<IllegalStateException> {
            storage.stable.setWithTransaction(2, assistantMessage("temporary")) {
                storage.timestamp.setWithTransaction(2, timestamp(2)) {
                    error("boom")
                }
            }
        }

        assertEquals(listOf(1), storage.stable.indexes().toList())
        assertEquals(-1, storage.timestamp.latestIndex())
    }

    test("set transaction compensates cancellation") {
        val storage = storage()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            storage.stable.setWithTransaction(1, userMessage("temporary")) {
                storage.timestamp.setWithTransaction(1, timestamp(1)) {
                    awaitCancellation()
                }
            }
        }

        job.cancelAndJoin()

        assertEquals(-1, storage.stable.latestIndex())
        assertEquals(-1, storage.timestamp.latestIndex())
    }

    test("revert transaction restores the original suffix on failure") {
        val storage = storage()
        val first = userMessage("first")
        val third = assistantMessage("third")
        storage.stable[1] = first
        storage.stable[3] = third

        assertFailsWith<IllegalStateException> {
            storage.stable.revertWithTransaction(2) {
                storage.stable[2] = assistantMessage("replacement")
                error("boom")
            }
        }

        assertEquals(listOf(1, 3), storage.stable.indexes().toList())
        assertEquals(first, storage.stable[1])
        assertEquals(third, storage.stable[3])
    }

    test("storage revert removes every clean timeline suffix") {
        val storage = storage()
        storage.stable[1] = userMessage("first")
        storage.unstable[2] = listOf(pendingTool("call"))
        storage.timestamp[3] = timestamp(3)
        storage.tokenCount[4] = 40L
        storage.settings[5] = settings("later")

        storage.revert(2)

        assertEquals(listOf(1), storage.stable.indexes().toList())
        assertEquals(-1, storage.unstable.latestIndex())
        assertEquals(-1, storage.timestamp.latestIndex())
        assertEquals(-1, storage.tokenCount.latestIndex())
        assertEquals(0, storage.settings.latestIndex())
    }

    test("global index helpers merge the six timelines") {
        val storage = storage()
        storage.stable[2] = userMessage("two")
        storage.timestamp[4] = timestamp(4)
        storage.unstable[6] = listOf(pendingTool("six"))

        assertEquals(6, storage.latestIndex())
        assertEquals(4, storage.floorToIndex(5))
        assertEquals(6, storage.ceilToIndex(5))
        assertEquals(6, storage.nextIndex(4))
        assertEquals(4, storage.prevIndex(6))
    }

    test("fork resets target and copies only indexes below boundary") {
        val source = storage(settings("source"))
        val target = storage(settings("target"))
        source.stable[1] = userMessage("first")
        source.unstable[2] = listOf(pendingTool("pending"))
        source.stable[3] = assistantMessage("future")
        target.stable[1] = userMessage("stale")

        source.forkTo(until = 3, target = target)

        assertEquals(settings("source"), target.settings[0])
        assertEquals(userMessage("first"), target.stable[1])
        assertEquals(listOf(pendingTool("pending")), target.unstable[2])
        assertEquals(listOf(1), target.stable.indexes().toList())
        assertFailsWith<IllegalArgumentException> {
            source.forkTo(until = 0, target = target)
        }
    }

    test("append compaction checkpoint stores replacement data and resets token count") {
        val storage = storage()
        val previous = storage.compaction[0]
        val retained = listOf(userMessage("retained"))
        val compaction = ResponseItem.Compaction(encryptedContent = "encrypted")
        val nextSettings = settings("next")
        storage.tokenCount[0] = 99L

        val index = storage.appendCompactionCheckpoint(
            prefix = retained,
            compaction = compaction,
            timestamp = timestamp(10),
            previousCheckpoint = previous,
            nextWindowId = "window-1",
            settings = nextSettings,
        )

        assertEquals(1, index)
        assertEquals(
            CleanCompactionCheckpoint(
                prefix = retained,
                compaction = compaction,
                historyBaseIndex = 2,
                windowNumber = 1,
                firstWindowId = previous.firstWindowId,
                previousWindowId = previous.windowId,
                windowId = "window-1",
            ),
            storage.compaction[index],
        )
        assertEquals(StableCleanEvent.ContextCompaction, storage.stable[index])
        assertEquals(nextSettings, storage.settings[index])
        assertEquals(0L, storage.tokenCount[index])
        assertEquals(index, storage.tokenCount.latestIndex())
        assertEquals(timestamp(10), storage.timestamp[index])
        assertEquals(
            retained.flatMap(StableCleanEvent::toResponseHistoryItems) + compaction,
            storage.compaction[index].toResponseHistoryItems(),
        )
    }
}
