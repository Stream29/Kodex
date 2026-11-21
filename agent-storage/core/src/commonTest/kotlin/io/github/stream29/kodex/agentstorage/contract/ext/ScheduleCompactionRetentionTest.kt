package io.github.stream29.kodex.agentstorage.contract.ext

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CompactionRetainedItem
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAwait
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAwaitEventResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableContextCompaction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableScheduleEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSubagentCompletedEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSubagentCreatedEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTerminalExitedEvent
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.tool.awaitevent.AwaitEventArguments
import io.github.stream29.kodex.tool.awaitevent.AwaitEventInput
import kotlin.test.assertEquals

val scheduleCompactionRetentionTest by testSuite {
    test("creation and not completion survives across the first and successive checkpoints") {
        val storage = InMemoryKodexAgentStorage.empty()
        val first = StableSubagentCreatedEvent(71, "Review", "First task")
        val second = StableSubagentCreatedEvent(72, "Verify", "Second task")
        val firstIndex = storage.commit(first)
        val i0 = storage.commit(StableSubagentCompletedEvent(71))
        val firstCheckpoint = storage.buildCompactionRetainedItems(i0)
        assertEquals(listOf<CompactionRetainedItem>(first.copy(prompt = "")), firstCheckpoint)
        assertEquals(first, storage.getFirstCommitsIn(firstIndex..firstIndex, setOf(first.commitType), 1).single().commit)
        storage.commit(StableContextCompaction(encryptedContent = "first", retainedItems = firstCheckpoint))
        storage.commit(second)
        val i1 = storage.commit(StableSubagentCompletedEvent(72))
        val retained = storage.buildCompactionRetainedItems(i1)
        assertEquals(listOf<CompactionRetainedItem>(first.copy(prompt = ""), second.copy(prompt = "")), retained)
        val i2 = storage.commit(StableContextCompaction(encryptedContent = "second", retainedItems = retained))
        assertEquals(retained, storage.buildCompactionRetainedItems(i2))
    }

    test("a creation notice is charged against the same retention budget") {
        val storage = InMemoryKodexAgentStorage.empty()
        val spawn = StableSubagentCreatedEvent(73)
        storage.commit(spawn)
        val prefix = "schedule #2 fired: "
        val fillsBudget = StableScheduleEvent(2, "x".repeat(256_000 - prefix.length))
        val index = storage.commit(fillsBudget)
        assertEquals(listOf<CompactionRetainedItem>(fillsBudget), storage.buildCompactionRetainedItems(index))
    }

    test("schedules survive repeated compaction but await and terminal exit do not") {
        val storage = InMemoryKodexAgentStorage.empty()
        val first = StableScheduleEvent(1, "")
        val second = StableScheduleEvent(2, "next task")
        storage.commit(first)
        storage.commit(
            StableAwait(
                "await", null,
                AwaitEventArguments(listOf(AwaitEventInput.TerminalExited(37)), name = "Wait for fixture"),
                StableAwaitEventResult.Failure("fixture"),
            ),
        )
        val terminalIndex = storage.commit(StableTerminalExitedEvent(37, name = "Run fixture", command = "fixture", exitCode = 0))
        val retained = storage.buildCompactionRetainedItems(terminalIndex)
        assertEquals(listOf<CompactionRetainedItem>(first), retained)
        storage.commit(StableContextCompaction(encryptedContent = "first checkpoint", retainedItems = retained))
        val secondIndex = storage.commit(second)
        val repeated = storage.buildCompactionRetainedItems(secondIndex)
        assertEquals(listOf<CompactionRetainedItem>(first, second), repeated)
        val checkpoint = storage.commit(StableContextCompaction(encryptedContent = "second checkpoint", retainedItems = repeated))
        assertEquals(repeated, storage.buildCompactionRetainedItems(checkpoint))
    }

    test("the schedule's full model input is charged to the 64000 token budget") {
        val storage = InMemoryKodexAgentStorage.empty()
        storage.commit(StableScheduleEvent(1, "older"))
        val prefix = "schedule #2 fired: "
        val exact = StableScheduleEvent(2, "x".repeat(256_000 - prefix.length))
        assertEquals(
            listOf<CompactionRetainedItem>(exact),
            storage.buildCompactionRetainedItems(storage.commit(exact)),
        )
        val overBudget = exact.copy(description = exact.description + "x")
        assertEquals(emptyList(), storage.buildCompactionRetainedItems(storage.commit(overBudget)))
    }
}
