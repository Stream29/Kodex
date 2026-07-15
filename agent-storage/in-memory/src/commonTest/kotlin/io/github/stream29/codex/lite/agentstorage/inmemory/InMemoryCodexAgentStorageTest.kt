package io.github.stream29.codex.lite.agentstorage.inmemory

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.codex.lite.agentstorage.contract.appendCompactionCheckpoint
import io.github.stream29.codex.lite.agentstorage.contract.ceilToIndex
import io.github.stream29.codex.lite.agentstorage.contract.floorToIndex
import io.github.stream29.codex.lite.agentstorage.contract.forkTo
import io.github.stream29.codex.lite.agentstorage.contract.indexes
import io.github.stream29.codex.lite.agentstorage.contract.indexesDescending
import io.github.stream29.codex.lite.agentstorage.contract.latestIndex
import io.github.stream29.codex.lite.agentstorage.contract.nextIndex
import io.github.stream29.codex.lite.agentstorage.contract.prevIndex
import io.github.stream29.codex.lite.agentstorage.contract.transaction
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.CompactionCheckpoint
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.PlanItemArg
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.StepStatus
import io.github.stream29.codex.lite.openai.UpdatePlanArgs
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
    initialSettings: CodexAgentSettings = settings("initial-model"),
): InMemoryCodexAgentStorage =
    InMemoryCodexAgentStorage(initialSettings)

private fun settings(model: String): CodexAgentSettings =
    CodexAgentSettings(
        model = OpenAiModelId(model),
        turnId = "turn-$model",
    )

private fun plan(
    step: String,
    status: StepStatus,
): UpdatePlanArgs =
    UpdatePlanArgs(
        explanation = "plan update",
        plan = listOf(PlanItemArg(step, status)),
    )

private fun timestamp(seconds: Long): Instant =
    Instant.fromEpochSeconds(seconds)

private fun checkpoint(
    prefix: List<ResponseItem.HistoryItem> = emptyList(),
    historyBaseIndex: Int = 0,
    windowNumber: Long = 0,
    firstWindowId: String = "window-0",
    previousWindowId: String? = null,
    windowId: String = firstWindowId,
): CompactionCheckpoint =
    CompactionCheckpoint(
        prefix = prefix,
        historyBaseIndex = historyBaseIndex,
        windowNumber = windowNumber,
        firstWindowId = firstWindowId,
        previousWindowId = previousWindowId,
        windowId = windowId,
    )

private fun userMessage(text: String): ResponseItem.Message =
    ResponseItem.Message(
        role = MessageRole.User,
        content = listOf(ContentItem.InputText(text)),
    )

private fun assistantMessage(text: String): ResponseItem.Message =
    ResponseItem.Message(
        role = MessageRole.Assistant,
        content = listOf(ContentItem.OutputText(text)),
    )

val inMemoryCodexAgentStorageTest by testSuite {
    test("construction publishes legal initial snapshot and new thread id") {
        val storage = storage()
        val other = storage()

        assertEquals(0, storage.latestIndex())
        assertEquals(settings("initial-model"), storage.settings[0])
        assertEquals(emptyList(), storage.compaction[0].prefix)
        assertEquals(0, storage.compaction[0].historyBaseIndex)
        assertEquals(0L, storage.compaction[0].windowNumber)
        assertEquals(-1, storage.history.latestIndex())
        assertNotEquals(storage.id, other.id)
    }

    test("history uses sparse timeline and rejects non tail writes") {
        val storage = storage()
        val first = userMessage("first")
        val second = assistantMessage("second")

        assertEquals(-1, storage.history.latestIndex())

        storage.history[0] = first
        storage.history[3] = second

        assertEquals(3, storage.history.latestIndex())
        assertEquals(first, storage.history[0])
        assertEquals(first, storage.history[2])
        assertEquals(second, storage.history[3])
        assertEquals(null, storage.history.floorToIndex(-1))
        assertEquals(0, storage.history.floorToIndex(0))
        assertEquals(0, storage.history.floorToIndex(2))
        assertEquals(3, storage.history.floorToIndex(3))
        assertEquals(0, storage.history.ceilToIndex(-1))
        assertEquals(0, storage.history.ceilToIndex(0))
        assertEquals(3, storage.history.ceilToIndex(1))
        assertEquals(null, storage.history.ceilToIndex(4))
        assertEquals(3, storage.history.nextIndex(0))
        assertEquals(null, storage.history.nextIndex(3))
        assertEquals(0, storage.history.prevIndex(3))
        assertEquals(null, storage.history.prevIndex(0))
        assertEquals(listOf(0, 3), storage.history.indexes().toList())
        assertEquals(listOf(3), storage.history.indexes(from = 1).toList())
        assertEquals(listOf(3, 0), storage.history.indexesDescending(from = 3).toList())
        assertEquals(listOf(0), storage.history.indexesDescending(from = 2).toList())
        assertFailsWith<IllegalArgumentException> {
            storage.history[1] = userMessage("overwrite")
        }
        assertEquals(listOf(0, 3), storage.history.indexes().toList())
    }

    test("revert removes stored suffix and allows appending again") {
        val storage = storage()
        val first = userMessage("first")
        val replacement = assistantMessage("replacement")

        storage.history[0] = first
        storage.history[3] = assistantMessage("third")
        storage.history[5] = assistantMessage("fifth")

        storage.history.revert(untilExclusive = 3)

        assertEquals(0, storage.history.latestIndex())
        assertEquals(listOf(0), storage.history.indexes().toList())
        assertEquals(first, storage.history[8])

        storage.history[3] = replacement
        assertEquals(listOf(0, 3), storage.history.indexes().toList())
        assertEquals(replacement, storage.history[3])

        storage.history.revert(untilExclusive = 4)
        assertEquals(listOf(0, 3), storage.history.indexes().toList())

        storage.history.revert(untilExclusive = 0)
        assertEquals(-1, storage.history.latestIndex())
        assertEquals(emptyList(), storage.history.indexes().toList())
    }

    test("timeline transaction reverts appended entries on failure") {
        val storage = storage()
        val first = userMessage("first")
        storage.history[0] = first

        assertFailsWith<IllegalStateException> {
            storage.history.transaction {
                storage.history[2] = assistantMessage("temporary")
                error("fail transaction")
            }
        }

        assertEquals(0, storage.history.latestIndex())
        assertEquals(listOf(0), storage.history.indexes().toList())
        assertEquals(first, storage.history[2])
    }

    test("storage transaction reverts every timeline on failure") {
        val initialSettings = settings("initial-model")
        val storage = storage(initialSettings)
        val initialCheckpoint = storage.compaction[0]
        val initialTimestamp = timestamp(0)
        val initialMessage = userMessage("initial")
        storage.timestamp[0] = initialTimestamp
        storage.tokenCount[0] = 10
        storage.history[0] = initialMessage

        assertFailsWith<IllegalStateException> {
            storage.transaction {
                storage.settings[2] = settings("temporary-model")
                storage.compaction[2] = checkpoint(windowNumber = 1, windowId = "window-1")
                storage.timestamp[2] = timestamp(2)
                storage.tokenCount[2] = 20
                storage.history[2] = assistantMessage("temporary")
                error("fail transaction")
            }
        }

        assertEquals(0, storage.latestIndex())
        assertEquals(initialSettings, storage.settings[2])
        assertEquals(initialCheckpoint, storage.compaction[2])
        assertEquals(initialTimestamp, storage.timestamp[2])
        assertEquals(10, storage.tokenCount[2])
        assertEquals(initialMessage, storage.history[2])
    }

    test("storage transaction reverts every timeline on cancellation") {
        val storage = storage()
        storage.timestamp[0] = timestamp(0)
        storage.tokenCount[0] = 10
        storage.history[0] = userMessage("initial")

        val transaction = launch(start = CoroutineStart.UNDISPATCHED) {
            storage.transaction {
                storage.settings[2] = settings("temporary-model")
                storage.timestamp[2] = timestamp(2)
                storage.history[2] = assistantMessage("temporary")
                awaitCancellation()
            }
        }

        transaction.cancelAndJoin()

        assertEquals(0, storage.latestIndex())
        assertEquals(listOf(0), storage.settings.indexes().toList())
        assertEquals(listOf(0), storage.timestamp.indexes().toList())
        assertEquals(listOf(0), storage.history.indexes().toList())
    }

    test("sparse timelines return active value at requested index") {
        val initialSettings = settings("initial-model").copy(plan = plan("inspect", StepStatus.Pending))
        val storage = storage(initialSettings)
        val updatedSettings = settings("updated-model").copy(plan = plan("implement", StepStatus.InProgress))
        val initialTimestamp = timestamp(1)
        val updatedTimestamp = timestamp(3)

        storage.settings[3] = updatedSettings
        storage.timestamp[0] = initialTimestamp
        storage.timestamp[3] = updatedTimestamp
        storage.tokenCount[0] = 10
        storage.tokenCount[3] = 30

        assertEquals(3, storage.settings.latestIndex())
        assertEquals(initialSettings, storage.settings[0])
        assertEquals(initialSettings, storage.settings[2])
        assertEquals(updatedSettings, storage.settings[3])
        assertEquals(updatedSettings, storage.settings[8])
        assertEquals(listOf(0, 3), storage.settings.indexes().toList())
        assertEquals(listOf(3), storage.settings.indexes(from = 1).toList())
        assertFailsWith<IllegalArgumentException> {
            storage.settings[3] = settings("overwrite")
        }

        assertEquals(initialSettings.plan, storage.settings[2].plan)
        assertEquals(updatedSettings.plan, storage.settings[8].plan)

        assertEquals(3, storage.timestamp.latestIndex())
        assertEquals(initialTimestamp, storage.timestamp[2])
        assertEquals(updatedTimestamp, storage.timestamp[8])

        assertEquals(3, storage.tokenCount.latestIndex())
        assertEquals(10, storage.tokenCount[2])
        assertEquals(30, storage.tokenCount[8])
    }

    test("sparse timeline rejects reads before first stored index") {
        val storage = storage()

        storage.history[2] = assistantMessage("summary")

        assertFailsWith<IllegalArgumentException> {
            storage.history[1]
        }
        assertEquals(2, storage.history.latestIndex())
        assertEquals(listOf(2), storage.history.indexes().toList())
    }

    test("storage latest index uses common state index") {
        val storage = storage()

        assertEquals(0, storage.latestIndex())
        assertEquals(0, storage.floorToIndex(0))
        assertEquals(0, storage.ceilToIndex(0))
        assertEquals(null, storage.nextIndex(0))

        storage.history[1] = userMessage("hello")
        assertEquals(1, storage.latestIndex())
        assertEquals(1, storage.nextIndex(0))
        assertEquals(0, storage.prevIndex(1))

        storage.tokenCount[1] = 10
        storage.timestamp[1] = timestamp(1)
        assertEquals(1, storage.latestIndex())

        storage.settings[2] = settings("future-model")
        storage.tokenCount[2] = 30
        assertEquals(2, storage.latestIndex())
        assertEquals(2, storage.nextIndex(1))
        assertEquals(1, storage.prevIndex(2))

        storage.timestamp[2] = timestamp(2)
        assertEquals(2, storage.latestIndex())

        storage.history[4] = assistantMessage("future history")
        assertEquals(4, storage.latestIndex())
        assertEquals(4, storage.nextIndex(3))
        assertEquals(2, storage.prevIndex(4))
        assertEquals(null, storage.nextIndex(4))
    }

    test("fork resets target before copying and keeps target thread id") {
        val oldPlan = plan("old step", StepStatus.Completed)
        val oldSettings = settings("old-model").copy(plan = oldPlan)
        val source = storage(oldSettings)
        val target = storage(settings("target-model"))
        val targetId = target.id
        val newSettings = settings("new-model")

        source.history[1] = userMessage("first")
        source.timestamp[1] = timestamp(1)
        source.tokenCount[1] = 10
        source.settings[2] = newSettings
        source.history[2] = assistantMessage("second")

        target.history[1] = userMessage("stale")
        target.settings[2] = settings("stale-model")
        target.tokenCount[2] = 999

        source.forkTo(until = 2, target = target)

        assertEquals(targetId, target.id)
        assertEquals(1, target.latestIndex())
        assertEquals(userMessage("first"), target.history[1])
        assertEquals(userMessage("first"), target.history[2])
        assertEquals(oldSettings, target.settings[2])
        assertEquals(oldPlan, target.settings[1].plan)
        assertEquals(timestamp(1), target.timestamp[1])
        assertEquals(10, target.tokenCount[1])
        assertEquals(listOf(0), target.settings.indexes().toList())
        assertEquals(listOf(0), target.compaction.indexes().toList())
        assertEquals(listOf(1), target.history.indexes().toList())
        assertEquals(listOf(1), target.timestamp.indexes().toList())
        assertEquals(listOf(1), target.tokenCount.indexes().toList())
    }

    test("fork rejects an empty target snapshot") {
        val source = storage()
        val target = storage()

        assertFailsWith<IllegalArgumentException> {
            source.forkTo(until = 0, target = target)
        }
    }

    test("append compaction checkpoint publishes shared storage transition") {
        val storage = storage()
        val prefix = listOf(userMessage("summary"))
        val marker = ResponseItem.ContextCompaction(encryptedContent = "encrypted")
        val transitionTime = timestamp(4)
        val previousCheckpoint = CompactionCheckpoint(
            prefix = emptyList(),
            historyBaseIndex = 0,
            windowNumber = 6,
            firstWindowId = "window-0",
            previousWindowId = "window-5",
            windowId = "window-6",
        )
        val settings = settings("test-model")

        val index = storage.appendCompactionCheckpoint(
            prefix = prefix,
            marker = marker,
            timestamp = transitionTime,
            tokenCount = 42,
            previousCheckpoint = previousCheckpoint,
            nextWindowId = "window-7",
            settings = settings,
        )

        assertEquals(1, index)
        assertEquals(1, storage.latestIndex())
        assertEquals(marker, storage.history[1])
        assertEquals(
            CompactionCheckpoint(
                prefix = prefix,
                historyBaseIndex = 2,
                windowNumber = 7,
                firstWindowId = "window-0",
                previousWindowId = "window-6",
                windowId = "window-7",
            ),
            storage.compaction[1],
        )
        assertEquals(transitionTime, storage.timestamp[1])
        assertEquals(42, storage.tokenCount[1])
        assertEquals(settings, storage.settings[1])
    }
}
