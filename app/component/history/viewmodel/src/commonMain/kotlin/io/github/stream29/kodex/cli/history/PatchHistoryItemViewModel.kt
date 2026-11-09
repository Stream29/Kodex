package io.github.stream29.kodex.cli.history

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatchToolEvent
import io.github.stream29.kodex.app.history.contract.item.PatchHistoryItemHeader
import io.github.stream29.kodex.app.history.contract.item.PatchHistoryItemState
import io.github.stream29.kodex.app.history.contract.item.PatchHistoryItemViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class PatchHistoryItemViewModelImpl(
    override val index: Int,
    private val descriptor: HistoryItemDescriptor,
    private val context: HistoryItemLoadContext,
) : PatchHistoryItemViewModel, LoadableHistoryItem, ReleasableHistoryItem {
    private val patchEvent: StablePatchToolEvent?
    private val header: PatchHistoryItemHeader?
    private val mutableState: MutableStateFlow<PatchHistoryItemState>

    init {
        var resolvedEvent: StablePatchToolEvent? = null
        var resolvedHeader: PatchHistoryItemHeader? = null
        val initialState = try {
            val event = descriptor.event as? StablePatchToolEvent
                ?: error("History item $index is not a patch tool.")
            val itemHeader = event.toHistoryHeader(descriptor.elapsed)
            resolvedEvent = event
            resolvedHeader = itemHeader
            PatchHistoryItemState.Collapsed(header = itemHeader)
        } catch (failure: Throwable) {
            context.logFailure(descriptor, failure)
            PatchHistoryItemState.Failed
        }
        patchEvent = resolvedEvent
        header = resolvedHeader
        mutableState = MutableStateFlow(initialState)
    }

    override val state: StateFlow<PatchHistoryItemState> = mutableState.asStateFlow()

    override fun ensureLoaded() = Unit

    override fun expand() {
        val collapsed = mutableState.value as? PatchHistoryItemState.Collapsed ?: return
        val resolvedEvent = patchEvent ?: return
        if (context.isCurrent()) {
            mutableState.value = PatchHistoryItemState.Expanded(
                header = collapsed.header,
                event = resolvedEvent,
            )
        }
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

    override fun release() {
        collapse()
    }
}
