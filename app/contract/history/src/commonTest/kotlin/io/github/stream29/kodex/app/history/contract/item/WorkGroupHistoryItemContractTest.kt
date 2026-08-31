package io.github.stream29.kodex.app.history.contract.item

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

public class WorkGroupHistoryItemContractTest {
    @Test
    public fun expandedStateAcceptsAnyNonEmptyNewestFirstChildren() {
        val children = listOf<WorkGroupChildHistoryItemViewModel>(
            ReasoningHistoryItemViewModel(index = 10, elapsed = Duration.ZERO),
            TestToolHistoryItemViewModel(index = 7),
            TestPatchHistoryItemViewModel(index = 4),
        )

        val expanded = WorkGroupHistoryItemState.Expanded(
            children = children,
            elapsed = 5.milliseconds,
        )

        assertEquals(children, expanded.children)
        assertEquals(
            children.take(1),
            WorkGroupHistoryItemState.Expanded(
                children = children.take(1),
                elapsed = Duration.ZERO,
            ).children,
        )
        assertFailsWith<IllegalArgumentException> {
            WorkGroupHistoryItemState.Expanded(
                children = emptyList(),
                elapsed = Duration.ZERO,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WorkGroupHistoryItemState.Expanded(
                children = children.reversed(),
                elapsed = Duration.ZERO,
            )
        }
    }
}

private class TestToolHistoryItemViewModel(
    override val index: Int,
) : ToolHistoryItemViewModel {
    override val state: StateFlow<ToolHistoryItemState> =
        MutableStateFlow(ToolHistoryItemState.Loading(Job()))

    override fun expand() = Unit

    override fun collapse() = Unit
}

private class TestPatchHistoryItemViewModel(
    override val index: Int,
) : PatchHistoryItemViewModel {
    override val state: StateFlow<PatchHistoryItemState> =
        MutableStateFlow(PatchHistoryItemState.Loading(Job()))

    override fun expand() = Unit

    override fun collapse() = Unit
}
