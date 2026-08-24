package io.github.stream29.kodex.cli.history

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatchToolEvent
import io.github.stream29.kodex.app.history.contract.item.PatchHistoryItemState
import io.github.stream29.kodex.app.history.contract.item.PatchHistoryItemViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class PatchHistoryItemViewModelImpl(
    override val index: Int,
    private val descriptor: HistoryItemDescriptor,
    private val context: HistoryItemLoadContext,
) : PatchHistoryItemViewModel, LoadableHistoryItem {
    private val mutableState: MutableStateFlow<PatchHistoryItemState>
    private val initialLoadingJob: Job

    init {
        initialLoadingJob = context.launch(start = CoroutineStart.LAZY) {
            try {
                val event = context.read(index) as? StablePatchToolEvent
                    ?: error("History item $index is not a patch tool.")
                if (context.isCurrent()) {
                    mutableState.value = PatchHistoryItemState.Collapsed(
                        header = event.toHistoryHeader(descriptor.elapsed),
                    )
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Throwable) {
                if (context.isCurrent()) mutableState.value = PatchHistoryItemState.Failed
            }
        }
        mutableState = MutableStateFlow(PatchHistoryItemState.Loading(initialLoadingJob))
    }

    override val state: StateFlow<PatchHistoryItemState> = mutableState.asStateFlow()

    override fun ensureLoaded() {
        initialLoadingJob.start()
    }

    override fun expand() {
        val collapsed = mutableState.value as? PatchHistoryItemState.Collapsed ?: return
        lateinit var loadingJob: Job
        loadingJob = context.launch(start = CoroutineStart.LAZY) {
            try {
                val event = context.read(index) as? StablePatchToolEvent
                    ?: error("History item $index is not a patch tool.")
                if (context.isCurrent() && mutableState.value.isExpanding(loadingJob)) {
                    mutableState.value = PatchHistoryItemState.Expanded(
                        header = collapsed.header,
                        event = event,
                    )
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Throwable) {
                if (context.isCurrent() && mutableState.value.isExpanding(loadingJob)) {
                    mutableState.value = PatchHistoryItemState.Failed
                }
            }
        }
        mutableState.value = PatchHistoryItemState.Expanding(collapsed.header, loadingJob)
        loadingJob.start()
    }

    override fun collapse() {
        when (val current = mutableState.value) {
            is PatchHistoryItemState.Expanding -> {
                current.loadingJob.cancel()
                mutableState.value = PatchHistoryItemState.Collapsed(current.header)
            }

            is PatchHistoryItemState.Expanded ->
                mutableState.value = PatchHistoryItemState.Collapsed(current.header)

            else -> Unit
        }
    }

    private fun PatchHistoryItemState.isExpanding(job: Job): Boolean =
        this is PatchHistoryItemState.Expanding && loadingJob === job
}
