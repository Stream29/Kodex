package io.github.stream29.kodex.agentstorage.contract.ext

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CompactionRetainedItem
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAwait
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAwaitEventResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableContextCompaction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableScheduleEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTerminalExitedEvent
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.tool.awaitevent.AwaitEventArguments
import kotlin.test.assertEquals

val scheduleCompactionRetentionTest by testSuite {
    test("schedules survive repeated compaction but await and terminal exit do not") {
        val storage = InMemoryKodexAgentStorage.empty()
        val first = StableScheduleEvent(1, "")
        val second = StableScheduleEvent(2, "next task")
        storage.commit(first)
        storage.commit(StableAwait("await", null, AwaitEventArguments(listOf(37)), StableAwaitEventResult.Failure("fixture")))
        val terminalIndex = storage.commit(StableTerminalExitedEvent(37, command = "fixture", exitCode = 0))
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
