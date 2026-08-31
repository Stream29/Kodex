package io.github.stream29.kodex.cli.history

import io.github.stream29.kodex.app.history.contract.item.WorkGroupChildHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.WorkGroupHistoryItemState
import io.github.stream29.kodex.app.history.contract.item.WorkGroupHistoryItemViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A collapsed group retains only its sparse range, count, and elapsed time. Work payloads and
 * nested VMs are created only on explicit expansion, then released when the group closes.
 */
internal class WorkGroupHistoryItemViewModelImpl(
    override val indexRange: IntRange,
    override val itemCount: Int,
    private val groupElapsed: kotlin.time.Duration,
    private val context: HistoryItemLoadContext,
    private val childFactory: suspend () -> List<WorkGroupChildHistoryItemViewModel>,
) : WorkGroupHistoryItemViewModel, LoadableHistoryItem {
    init {
        require(!indexRange.isEmpty())
        require(itemCount > 0)
    }

    private val mutableState: MutableStateFlow<WorkGroupHistoryItemState>
    private val initialLoadingJob: Job

    init {
        initialLoadingJob = context.launch(start = CoroutineStart.LAZY) {
            if (context.isCurrent()) {
                mutableState.value = WorkGroupHistoryItemState.Collapsed(groupElapsed)
            }
        }
        mutableState = MutableStateFlow(WorkGroupHistoryItemState.Loading(initialLoadingJob))
    }

    override val state: StateFlow<WorkGroupHistoryItemState> = mutableState.asStateFlow()

    override fun ensureLoaded() {
        initialLoadingJob.start()
    }

    override fun expand() {
        val collapsed = mutableState.value as? WorkGroupHistoryItemState.Collapsed ?: return
        lateinit var loadingJob: Job
        loadingJob = context.launch(start = CoroutineStart.LAZY) {
            var children: List<WorkGroupChildHistoryItemViewModel> = emptyList()
            var retainedByExpandedState = false
            try {
                children = childFactory()
                check(children.size == itemCount) {
                    "History work group $indexRange expected $itemCount children, " +
                        "but loaded ${children.size}."
                }
                children.forEach { child -> child.ensureLoaded() }
                if (context.isCurrent() && mutableState.value.isExpanding(loadingJob)) {
                    mutableState.value = WorkGroupHistoryItemState.Expanded(
                        children = children,
                        elapsed = collapsed.elapsed,
                    )
                    retainedByExpandedState = true
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Throwable) {
                if (context.isCurrent() && mutableState.value.isExpanding(loadingJob)) {
                    mutableState.value = WorkGroupHistoryItemState.Failed
                }
            } finally {
                if (!retainedByExpandedState) {
                    children.forEach { child -> child.release() }
                }
            }
        }
        mutableState.value = WorkGroupHistoryItemState.Expanding(collapsed.elapsed, loadingJob)
        loadingJob.start()
    }

    override fun collapse() {
        when (val current = mutableState.value) {
            is WorkGroupHistoryItemState.Loading -> {
                current.loadingJob.cancel()
            }

            is WorkGroupHistoryItemState.Expanding -> {
                current.loadingJob.cancel()
                mutableState.value = WorkGroupHistoryItemState.Collapsed(current.elapsed)
            }

            is WorkGroupHistoryItemState.Expanded -> {
                current.children.forEach { child -> child.release() }
                mutableState.value = WorkGroupHistoryItemState.Collapsed(current.elapsed)
            }

            else -> Unit
        }
    }

    private fun WorkGroupHistoryItemState.isExpanding(job: Job): Boolean =
        this is WorkGroupHistoryItemState.Expanding && loadingJob === job
}
