package io.github.stream29.codex.lite.agentstorage.inmemory

import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.PlanItemArg
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.StepStatus
import io.github.stream29.codex.lite.openai.UpdatePlanArgs
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.CompactionCheckpoint
import io.github.stream29.codex.lite.agentstorage.contract.appendCompactionCheckpoint
import io.github.stream29.codex.lite.agentstorage.contract.forkTo
import io.github.stream29.codex.lite.agentstorage.contract.indexes
import io.github.stream29.codex.lite.agentstorage.contract.latestIndex
import io.github.stream29.codex.lite.agentstorage.contract.nextIndex
import io.github.stream29.codex.lite.agentstorage.contract.transaction
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class InMemoryCodexAgentStorageTest {
    @Test
    fun historyUsesSparseTimelineAndRejectsNonTailWrites() = runTest {
        val storage = InMemoryCodexAgentStorage()
        val first = userMessage("first")
        val second = assistantMessage("second")

        assertEquals(-1, storage.history.latestIndex())

        storage.history[0] = first
        storage.history[3] = second

        assertEquals(3, storage.history.latestIndex())
        assertEquals(first, storage.history[0])
        assertEquals(first, storage.history[2])
        assertEquals(second, storage.history[3])
        assertEquals(0, storage.history.nextIndex(from = 0))
        assertEquals(3, storage.history.nextIndex(from = 1))
        assertEquals(null, storage.history.nextIndex(from = 4))
        assertEquals(listOf(0, 3), storage.history.indexes().toList())
        assertEquals(listOf(3), storage.history.indexes(from = 1).toList())
        assertFailsWith<IllegalArgumentException> {
            storage.history[1] = userMessage("overwrite")
        }
        assertEquals(listOf(0, 3), storage.history.indexes().toList())
    }

    @Test
    fun revertRemovesStoredSuffixAndAllowsAppendingAgain() = runTest {
        val storage = InMemoryCodexAgentStorage()
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

    @Test
    fun timelineTransactionRevertsAppendedEntriesOnFailure() = runTest {
        val storage = InMemoryCodexAgentStorage()
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

    @Test
    fun storageTransactionRevertsEveryTimelineOnFailure() = runTest {
        val storage = InMemoryCodexAgentStorage()
        val initialSettings = settings("initial-model")
        val initialCheckpoint = checkpoint()
        val initialPlan = plan("initial", StepStatus.Pending)
        val initialTimestamp = timestamp(0)
        val initialMessage = userMessage("initial")
        storage.settings[0] = initialSettings
        storage.compaction[0] = initialCheckpoint
        storage.plan[0] = initialPlan
        storage.timestamp[0] = initialTimestamp
        storage.tokenCount[0] = 10
        storage.history[0] = initialMessage

        assertFailsWith<IllegalStateException> {
            storage.transaction {
                storage.settings[2] = settings("temporary-model")
                storage.compaction[2] = checkpoint(windowNumber = 1, windowId = "window-1")
                storage.plan[2] = plan("temporary", StepStatus.InProgress)
                storage.timestamp[2] = timestamp(2)
                storage.tokenCount[2] = 20
                storage.history[2] = assistantMessage("temporary")
                error("fail transaction")
            }
        }

        assertEquals(0, storage.latestIndex())
        assertEquals(initialSettings, storage.settings[2])
        assertEquals(initialCheckpoint, storage.compaction[2])
        assertEquals(initialPlan, storage.plan[2])
        assertEquals(initialTimestamp, storage.timestamp[2])
        assertEquals(10, storage.tokenCount[2])
        assertEquals(initialMessage, storage.history[2])
    }

    @Test
    fun storageTransactionRevertsEveryTimelineOnCancellation() = runTest {
        val storage = InMemoryCodexAgentStorage()
        storage.settings[0] = settings("initial-model")
        storage.compaction[0] = checkpoint()
        storage.plan[0] = plan("initial", StepStatus.Pending)
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

    @Test
    fun sparseTimelinesReturnActiveValueAtRequestedIndex() = runTest {
        val storage = InMemoryCodexAgentStorage()
        val initialSettings = settings("initial-model")
        val updatedSettings = settings("updated-model")
        val initialPlan = plan("inspect", StepStatus.Pending)
        val updatedPlan = plan("implement", StepStatus.InProgress)
        val initialTimestamp = timestamp(1)
        val updatedTimestamp = timestamp(3)

        storage.settings[0] = initialSettings
        storage.settings[3] = updatedSettings
        storage.plan[0] = initialPlan
        storage.plan[3] = updatedPlan
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

        assertEquals(3, storage.plan.latestIndex())
        assertEquals(initialPlan, storage.plan[2])
        assertEquals(updatedPlan, storage.plan[8])

        assertEquals(3, storage.timestamp.latestIndex())
        assertEquals(initialTimestamp, storage.timestamp[2])
        assertEquals(updatedTimestamp, storage.timestamp[8])

        assertEquals(3, storage.tokenCount.latestIndex())
        assertEquals(10, storage.tokenCount[2])
        assertEquals(30, storage.tokenCount[8])
    }

    @Test
    fun sparseTimelineRejectsReadsBeforeFirstStoredIndex() = runTest {
        val storage = InMemoryCodexAgentStorage()

        storage.compaction[2] = CompactionCheckpoint(
            prefix = listOf(assistantMessage("summary")),
            historyBaseIndex = 2,
            windowNumber = 0,
            firstWindowId = "window-0",
            windowId = "window-0",
        )

        assertFailsWith<IllegalArgumentException> {
            storage.compaction[1]
        }
        assertEquals(2, storage.compaction.latestIndex())
        assertEquals(listOf(2), storage.compaction.indexes().toList())
    }

    @Test
    fun storageLatestIndexUsesCommonStateIndex() = runTest {
        val storage = InMemoryCodexAgentStorage()

        assertEquals(-1, storage.latestIndex())
        assertEquals(null, storage.nextIndex(0))

        storage.history[0] = userMessage("hello")
        assertEquals(0, storage.latestIndex())
        assertEquals(0, storage.nextIndex(0))

        storage.settings[0] = settings("model")
        storage.compaction[0] = checkpoint()
        storage.plan[0] = UpdatePlanArgs(plan = emptyList())
        storage.tokenCount[0] = 10
        storage.timestamp[0] = timestamp(0)
        assertEquals(0, storage.latestIndex())

        storage.settings[2] = settings("future-model")
        storage.plan[2] = plan("future", StepStatus.Pending)
        storage.tokenCount[2] = 30
        assertEquals(2, storage.latestIndex())
        assertEquals(2, storage.nextIndex(1))

        storage.timestamp[2] = timestamp(2)
        assertEquals(2, storage.latestIndex())

        storage.history[4] = assistantMessage("future history")
        assertEquals(4, storage.latestIndex())
        assertEquals(4, storage.nextIndex(3))
        assertEquals(null, storage.nextIndex(5))
    }

    @Test
    fun forkCopiesEntriesBeforeExclusiveBoundary() = runTest {
        val source = InMemoryCodexAgentStorage()
        val target = InMemoryCodexAgentStorage()
        val oldSettings = settings("old-model")
        val newSettings = settings("new-model")
        val oldPlan = plan("old step", StepStatus.Completed)
        val newPlan = plan("new step", StepStatus.InProgress)
        val oldTimestamp = timestamp(0)
        val middleTimestamp = timestamp(1)
        val newTimestamp = timestamp(2)
        val checkpoint = checkpoint()

        source.settings[0] = oldSettings
        source.compaction[0] = checkpoint
        source.plan[0] = oldPlan
        source.timestamp[0] = oldTimestamp
        source.tokenCount[0] = 10
        source.history[0] = userMessage("first")
        source.history[1] = assistantMessage("second")
        source.timestamp[1] = middleTimestamp
        source.settings[2] = newSettings
        source.plan[2] = newPlan
        source.tokenCount[2] = 30
        source.history[2] = userMessage("third")
        source.timestamp[2] = newTimestamp

        source.forkTo(until = 2, target = target)

        assertEquals(1, target.latestIndex())
        assertEquals(userMessage("first"), target.history[0])
        assertEquals(assistantMessage("second"), target.history[1])
        assertEquals(oldSettings, target.settings[1])
        assertEquals(checkpoint, target.compaction[1])
        assertEquals(oldPlan, target.plan[1])
        assertEquals(middleTimestamp, target.timestamp[1])
        assertEquals(10, target.tokenCount[1])
        assertEquals(listOf(0), target.settings.indexes().toList())
        assertEquals(listOf(0), target.compaction.indexes().toList())
        assertEquals(listOf(0), target.plan.indexes().toList())
        assertEquals(listOf(0, 1), target.timestamp.indexes().toList())
        assertEquals(listOf(0), target.tokenCount.indexes().toList())
    }

    @Test
    fun appendCompactionCheckpointPublishesSharedStorageTransition() = runTest {
        val storage = InMemoryCodexAgentStorage()
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

        val index = storage.appendCompactionCheckpoint(
            prefix = prefix,
            marker = marker,
            timestamp = transitionTime,
            tokenCount = 42,
            previousCheckpoint = previousCheckpoint,
            nextWindowId = "window-7",
        )

        assertEquals(0, index)
        assertEquals(0, storage.latestIndex())
        assertEquals(marker, storage.history[0])
        assertEquals(
            CompactionCheckpoint(
                prefix = prefix,
                historyBaseIndex = 1,
                windowNumber = 7,
                firstWindowId = "window-0",
                previousWindowId = "window-6",
                windowId = "window-7",
            ),
            storage.compaction[0],
        )
        assertEquals(transitionTime, storage.timestamp[0])
        assertEquals(42, storage.tokenCount[0])
    }

    private fun settings(model: String): CodexAgentSettings =
        CodexAgentSettings(model = OpenAiModelId(model))

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
}
