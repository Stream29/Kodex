package io.github.stream29.kodex.cli.history

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.app.history.contract.item.ToolHistoryItemHeader
import io.github.stream29.kodex.app.history.contract.item.ToolHistoryItemState
import io.github.stream29.kodex.app.history.contract.item.ToolHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.isOrdinaryHistoryToolEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class ToolHistoryItemViewModelImpl(
    override val index: Int,
    private val descriptor: HistoryItemDescriptor,
    private val context: HistoryItemLoadContext,
) : ToolHistoryItemViewModel, LoadableHistoryItem, ReleasableHistoryItem {
    private val tool: StableCleanEvent.CompletedTool?
    private val header: ToolHistoryItemHeader?
    private val mutableState: MutableStateFlow<ToolHistoryItemState>

    init {
        var resolvedTool: StableCleanEvent.CompletedTool? = null
        var resolvedHeader: ToolHistoryItemHeader? = null
        val initialState = try {
            val completed = descriptor.event as? StableCleanEvent.CompletedTool
                ?: error("History item $index is not a completed tool.")
            check(completed.isOrdinaryHistoryTool()) {
                "History item $index is not an ordinary tool."
            }
            val itemHeader = completed.toHistoryHeader(descriptor.elapsed)
            resolvedTool = completed
            resolvedHeader = itemHeader
            ToolHistoryItemState.Collapsed(header = itemHeader)
        } catch (failure: Throwable) {
            context.logFailure(descriptor, failure)
            ToolHistoryItemState.Failed
        }
        tool = resolvedTool
        header = resolvedHeader
        mutableState = MutableStateFlow(initialState)
    }

    override val state: StateFlow<ToolHistoryItemState> = mutableState.asStateFlow()

    override fun ensureLoaded() = Unit

    override fun expand() {
        val collapsed = mutableState.value as? ToolHistoryItemState.Collapsed ?: return
        val resolvedTool = tool ?: return
        if (context.isCurrent()) {
            mutableState.value = ToolHistoryItemState.Expanded(
                header = collapsed.header,
                event = resolvedTool,
            )
        }
    }

    override fun collapse() {
        when (val current = mutableState.value) {
            is ToolHistoryItemState.Expanding -> {
                current.loadingJob.cancel()
                mutableState.value = ToolHistoryItemState.Collapsed(current.header)
            }

            is ToolHistoryItemState.Expanded ->
                mutableState.value = ToolHistoryItemState.Collapsed(current.header)

            else -> Unit
        }
    }

    override fun release() {
        collapse()
    }
}

internal fun StableCleanEvent.CompletedTool.isOrdinaryHistoryTool(): Boolean = isOrdinaryHistoryToolEvent
