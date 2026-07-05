package io.github.stream29.codex.lite.agentstorage.inmemory

import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.PlanItemArg
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.StepStatus
import io.github.stream29.codex.lite.openai.UpdatePlanArgs
import io.github.stream29.codex.lite.agentstorage.contract.CodexAgentSettings
import io.github.stream29.codex.lite.agentstorage.contract.CompactionCheckpoint
import io.github.stream29.codex.lite.agentstorage.contract.forkTo
import io.github.stream29.codex.lite.agentstorage.contract.indexes
import io.github.stream29.codex.lite.agentstorage.contract.latestIndex
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class InMemoryCodexAgentStorageTest {
    @Test
    fun historyUsesSparseAppendOnlyTimeline() = runTest {
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
        )

        assertFailsWith<IllegalArgumentException> {
            storage.compaction[1]
        }
        assertEquals(2, storage.compaction.latestIndex())
        assertEquals(listOf(2), storage.compaction.indexes().toList())
    }

    @Test
    fun storageLatestIndexUsesHistoryBoundary() = runTest {
        val storage = InMemoryCodexAgentStorage()

        storage.settings[0] = settings("model")
        storage.compaction[0] = CompactionCheckpoint(prefix = emptyList(), historyBaseIndex = 0)
        storage.plan[0] = UpdatePlanArgs(plan = emptyList())
        storage.timestamp[0] = timestamp(0)
        storage.tokenCount[0] = 10

        assertEquals(-1, storage.latestIndex())

        storage.history[0] = userMessage("hello")
        storage.settings[2] = settings("future-model")
        storage.plan[2] = plan("future", StepStatus.Pending)
        storage.timestamp[2] = timestamp(2)
        storage.tokenCount[2] = 30

        assertEquals(0, storage.latestIndex())
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
        val newTimestamp = timestamp(2)
        val checkpoint = CompactionCheckpoint(prefix = emptyList(), historyBaseIndex = 0)

        source.settings[0] = oldSettings
        source.compaction[0] = checkpoint
        source.plan[0] = oldPlan
        source.timestamp[0] = oldTimestamp
        source.tokenCount[0] = 10
        source.history[0] = userMessage("first")
        source.history[1] = assistantMessage("second")
        source.settings[2] = newSettings
        source.plan[2] = newPlan
        source.timestamp[2] = newTimestamp
        source.tokenCount[2] = 30
        source.history[2] = userMessage("third")

        source.forkTo(until = 2, target = target)

        assertEquals(1, target.latestIndex())
        assertEquals(userMessage("first"), target.history[0])
        assertEquals(assistantMessage("second"), target.history[1])
        assertEquals(oldSettings, target.settings[1])
        assertEquals(checkpoint, target.compaction[1])
        assertEquals(oldPlan, target.plan[1])
        assertEquals(oldTimestamp, target.timestamp[1])
        assertEquals(10, target.tokenCount[1])
        assertEquals(listOf(0), target.settings.indexes().toList())
        assertEquals(listOf(0), target.compaction.indexes().toList())
        assertEquals(listOf(0), target.plan.indexes().toList())
        assertEquals(listOf(0), target.timestamp.indexes().toList())
        assertEquals(listOf(0), target.tokenCount.indexes().toList())
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
