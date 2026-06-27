package io.github.stream29.codex.lite.storage.inmemory

import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.storage.contract.CodexAgentSettings
import io.github.stream29.codex.lite.storage.contract.CompactionCheckpoint
import io.github.stream29.codex.lite.storage.contract.forkTo
import io.github.stream29.codex.lite.storage.contract.latestIndex
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InMemoryCodexAgentRawDataStorageTest {
    @Test
    fun historyUsesSparseAppendOnlyTimeline() = runTest {
        val storage = InMemoryCodexAgentRawDataStorage()
        val first = userMessage("first")
        val second = assistantMessage("second")

        assertEquals(-1, storage.history.latestIndex())

        storage.history[0] = first
        storage.history[3] = second

        assertEquals(3, storage.history.latestIndex())
        assertEquals(first, storage.history[0])
        assertEquals(first, storage.history[2])
        assertEquals(second, storage.history[3])
        assertEquals(listOf(0, 3), storage.history.indexes().toList())
        assertEquals(listOf(3), storage.history.indexes(from = 1).toList())
        assertFailsWith<IllegalArgumentException> {
            storage.history[1] = userMessage("overwrite")
        }
        assertEquals(listOf(0, 3), storage.history.indexes().toList())
    }

    @Test
    fun sparseTimelinesReturnActiveValueAtRequestedIndex() = runTest {
        val storage = InMemoryCodexAgentRawDataStorage()
        val initialSettings = settings("initial-model")
        val updatedSettings = settings("updated-model")

        storage.settings[0] = initialSettings
        storage.settings[3] = updatedSettings

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
    }

    @Test
    fun sparseTimelineRejectsReadsBeforeFirstStoredIndex() = runTest {
        val storage = InMemoryCodexAgentRawDataStorage()

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
        val storage = InMemoryCodexAgentRawDataStorage()

        storage.settings[0] = settings("model")
        storage.compaction[0] = CompactionCheckpoint(prefix = emptyList(), historyBaseIndex = 0)

        assertEquals(-1, storage.latestIndex())

        storage.history[0] = userMessage("hello")
        storage.settings[2] = settings("future-model")

        assertEquals(0, storage.latestIndex())
    }

    @Test
    fun forkCopiesEntriesBeforeExclusiveBoundary() = runTest {
        val source = InMemoryCodexAgentRawDataStorage()
        val target = InMemoryCodexAgentRawDataStorage()
        val oldSettings = settings("old-model")
        val newSettings = settings("new-model")
        val checkpoint = CompactionCheckpoint(prefix = emptyList(), historyBaseIndex = 0)

        source.settings[0] = oldSettings
        source.compaction[0] = checkpoint
        source.history[0] = userMessage("first")
        source.history[1] = assistantMessage("second")
        source.settings[2] = newSettings
        source.history[2] = userMessage("third")

        source.forkTo(until = 2, target = target)

        assertEquals(1, target.latestIndex())
        assertEquals(userMessage("first"), target.history[0])
        assertEquals(assistantMessage("second"), target.history[1])
        assertEquals(oldSettings, target.settings[1])
        assertEquals(checkpoint, target.compaction[1])
        assertEquals(listOf(0), target.settings.indexes().toList())
        assertEquals(listOf(0), target.compaction.indexes().toList())
    }

    private fun settings(model: String): CodexAgentSettings =
        CodexAgentSettings(model = OpenAiModelId(model))

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
