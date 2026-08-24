package io.github.stream29.kodex.cli.history

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.app.history.contract.item.ToolHistoryItemState
import io.github.stream29.kodex.app.history.contract.item.ToolHistoryItemViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class ToolHistoryItemViewModelImpl(
    override val index: Int,
    private val descriptor: HistoryItemDescriptor,
    private val context: HistoryItemLoadContext,
) : ToolHistoryItemViewModel, LoadableHistoryItem {
    private val mutableState: MutableStateFlow<ToolHistoryItemState>
    private val initialLoadingJob: Job

    init {
        initialLoadingJob = context.launch(start = CoroutineStart.LAZY) {
            try {
                val event = context.read(index)
                val tool = event as? StableCleanEvent.CompletedTool
                    ?: error("History item $index is not a completed tool.")
                check(tool.isOrdinaryHistoryTool()) {
                    "History item $index is not an ordinary tool."
                }
                if (context.isCurrent()) {
                    mutableState.value = ToolHistoryItemState.Collapsed(
                        header = tool.toHistoryHeader(descriptor.elapsed),
                    )
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Throwable) {
                if (context.isCurrent()) mutableState.value = ToolHistoryItemState.Failed
            }
        }
        mutableState = MutableStateFlow(ToolHistoryItemState.Loading(initialLoadingJob))
    }

    override val state: StateFlow<ToolHistoryItemState> = mutableState.asStateFlow()

    override fun ensureLoaded() {
        initialLoadingJob.start()
    }

    override fun expand() {
        val collapsed = mutableState.value as? ToolHistoryItemState.Collapsed ?: return
        lateinit var loadingJob: Job
        loadingJob = context.launch(start = CoroutineStart.LAZY) {
            try {
                val event = context.read(index)
                val tool = event as? StableCleanEvent.CompletedTool
                    ?: error("History item $index is not a completed tool.")
                check(tool.isOrdinaryHistoryTool()) {
                    "History item $index is not an ordinary tool."
                }
                if (context.isCurrent() && mutableState.value.isExpanding(loadingJob)) {
                    mutableState.value = ToolHistoryItemState.Expanded(
                        header = collapsed.header,
                        event = tool,
                    )
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Throwable) {
                if (context.isCurrent() && mutableState.value.isExpanding(loadingJob)) {
                    mutableState.value = ToolHistoryItemState.Failed
                }
            }
        }
        mutableState.value = ToolHistoryItemState.Expanding(collapsed.header, loadingJob)
        loadingJob.start()
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

    private fun ToolHistoryItemState.isExpanding(job: Job): Boolean =
        this is ToolHistoryItemState.Expanding && loadingJob === job
}

internal fun StableCleanEvent.CompletedTool.isOrdinaryHistoryTool(): Boolean = when (this) {
    is io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatchToolEvent,
    is io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate,
    is io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputToolEvent,
        -> false

    else -> true
}
