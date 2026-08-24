package io.github.stream29.kodex.app.history.contract.item

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.kodex.openai.UpdatePlanArgs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

public class ExpandableHistoryItemContractTest {
    @Test
    public fun loadedHeadersRetainOnlyValidatedOneLinePresentation() {
        val tool = ToolHistoryItemHeader.Summary(
            summary = "Run tests",
            status = "completed",
            elapsed = 12.milliseconds,
        )
        val patch = PatchHistoryItemHeader(
            target = PatchHistoryItemTarget.FileCount(2),
            status = PatchHistoryItemStatus.Completed,
            elapsed = 4.milliseconds,
        )

        assertEquals("Run tests", tool.summary)
        assertEquals(PatchHistoryItemTarget.FileCount(2), patch.target)
        assertFailsWith<IllegalArgumentException> {
            tool.copy(summary = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            patch.copy(elapsed = Duration.INFINITE)
        }
    }

    @Test
    public fun ordinaryToolStateRejectsSpecializedBreakerEvents() {
        val header = ToolHistoryItemHeader.Summary(
            summary = "Update the plan",
            status = "completed",
            elapsed = Duration.ZERO,
        )

        assertFailsWith<IllegalArgumentException> {
            ToolHistoryItemState.Expanded(
                header = header,
                event = StablePlanUpdate(
                    callId = "call",
                    arguments = UpdatePlanArgs(plan = emptyList()),
                ),
            )
        }
    }

    @Test
    public fun immutableItemsAndReadyStatesRejectInvalidDurations() {
        assertFailsWith<IllegalArgumentException> {
            ReasoningHistoryItemViewModel(
                index = 1,
                elapsed = (-1).milliseconds,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ContextCompactionHistoryItemViewModel(
                index = 1,
                elapsed = Duration.INFINITE,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TurnTimeMarkerHistoryItemViewModel(
                markerIndex = 1,
                endIndex = 2,
                duration = (-1).milliseconds,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WorkGroupHistoryItemState.Collapsed(Duration.INFINITE)
        }
    }
}
