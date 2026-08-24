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
 * A collapsed group retains descriptors, not nested VMs. Expanded children are group-local and
 * released when the group closes, keeping the collapsed resource footprint minimal.
 */
internal class WorkGroupHistoryItemViewModelImpl(
    descriptors: List<HistoryItemDescriptor>,
    private val groupElapsed: kotlin.time.Duration,
    private val context: HistoryItemLoadContext,
    private val childFactory: (HistoryItemDescriptor) -> WorkGroupChildHistoryItemViewModel,
) : WorkGroupHistoryItemViewModel, LoadableHistoryItem {
    private val descriptors = descriptors.toList()

    init {
        require(this.descriptors.size > 1)
        require(this.descriptors.all(HistoryItemDescriptor::isFoldable))
    }

    override val indexRange: IntRange =
        descriptors.last().index..descriptors.first().index

    override val itemCount: Int = descriptors.size

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
                children = descriptors.map(childFactory)
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
