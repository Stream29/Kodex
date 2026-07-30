package io.github.stream29.kodex.cli.history

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingFunctionToolEvent
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.OpenAiModelId
import kotlinx.serialization.json.JsonObject
import kotlin.test.assertEquals
import kotlin.test.assertNull

val agentHistoryModelsTest by testSuite {
    test("loads a finite newest-first batch across sparse indexes") {
        val storage = InMemoryKodexAgentStorage(
            KodexAgentSettings(OpenAiModelId("test-model")),
        )
        storage.stable[1] = userMessage("one")
        storage.stable[4] = userMessage("four")
        storage.stable[9] = userMessage("nine")

        val newest = loadHistoryBatch(
            storage = storage,
            fromInclusive = 12,
            limit = 2,
        )

        assertEquals(listOf(9, 4), newest.entries.map(AgentHistoryStoredEntry::index))
        assertEquals(1, newest.nextOlderIndex)

        val older = loadHistoryBatch(
            storage = storage,
            fromInclusive = newest.nextOlderIndex!!,
            limit = 2,
        )

        assertEquals(listOf(1), older.entries.map(AgentHistoryStoredEntry::index))
        assertNull(older.nextOlderIndex)
    }

    test("does not read stable history past the requested snapshot") {
        val storage = InMemoryKodexAgentStorage(
            KodexAgentSettings(OpenAiModelId("test-model")),
        )
        storage.stable[2] = userMessage("visible")
        storage.stable[8] = userMessage("future")

        val batch = loadHistoryBatch(
            storage = storage,
            fromInclusive = 5,
            limit = 4,
        )

        assertEquals(listOf(2), batch.entries.map(AgentHistoryStoredEntry::index))
        assertNull(batch.nextOlderIndex)
    }

    test("reads only the unfinished snapshot visible at the requested index") {
        val storage = InMemoryKodexAgentStorage(
            KodexAgentSettings(OpenAiModelId("test-model")),
        )
        val pending = PendingFunctionToolEvent(
            callId = "call",
            name = "demo",
            arguments = JsonObject(emptyMap()),
        )
        storage.unstable[4] = listOf(pending)

        assertEquals(emptyList(), loadPendingTail(storage, 3))
        assertEquals(listOf(pending), loadPendingTail(storage, 9))
    }
}

private fun userMessage(text: String): StableCleanEvent.UserMessage =
    StableCleanEvent.UserMessage(
        content = listOf(ContentItem.InputText(text)),
    )
