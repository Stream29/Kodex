package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.jakewharton.mosaic.ui.MosaicComposable
import com.jakewharton.mosaic.ui.SubcomposeLayoutState
import com.jakewharton.mosaic.ui.unit.Constraints
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield

internal class LazyColumnPrefetchState {
    private val planState = mutableStateOf<LazyColumnPrefetchPlan?>(null)
    val plan: State<LazyColumnPrefetchPlan?> get() = planState

    private val prepared = mutableMapOf<LazyColumnPrefetchRequest, PreparedLazyItem>()

    fun schedule(plan: LazyColumnPrefetchPlan?) {
        if (planState.value == plan) return
        planState.value = plan
        val retainedRequests = plan?.requests?.toSet().orEmpty()
        val iterator = prepared.iterator()
        while (iterator.hasNext()) {
            val (request, item) = iterator.next()
            if (request !in retainedRequests) {
                item.handle.dispose()
                iterator.remove()
            }
        }
    }

    fun install(item: PreparedLazyItem): Boolean {
        if (!isScheduled(item.request)) {
            item.handle.dispose()
            return false
        }
        prepared.put(item.request, item)?.handle?.dispose()
        return true
    }

    fun preparedFor(request: LazyColumnPrefetchRequest): PreparedLazyItem? =
        prepared[request]

    fun isScheduled(request: LazyColumnPrefetchRequest): Boolean =
        request in planState.value?.requests.orEmpty()

    fun markPremeasured(item: PreparedLazyItem, height: Int) {
        if (prepared[item.request] === item) {
            item.premeasuredHeight = height
        }
    }

    fun contentFor(
        provider: LazyItemProvider,
        layoutIndex: Int,
        key: Any,
    ): (@Composable @MosaicComposable () -> Unit)? {
        val entry = prepared.entries.firstOrNull { (request, _) ->
            request.provider === provider &&
                request.layoutIndex == layoutIndex &&
                request.key == key
        } ?: return null
        val item = entry.value
        prepared.remove(entry.key)
        return item.content
    }

    fun cancel(request: LazyColumnPrefetchRequest) {
        prepared.remove(request)?.handle?.dispose()
    }

    fun dispose() {
        planState.value = null
        prepared.values.forEach { item -> item.handle.dispose() }
        prepared.clear()
    }
}

internal data class LazyColumnPrefetchPlan(
    val targetRows: Int,
    val requests: List<LazyColumnPrefetchRequest>,
)

internal data class LazyColumnPrefetchRequest(
    val provider: LazyItemProvider,
    val layoutIndex: Int,
    val key: Any,
    val contentType: Any?,
    val constraints: Constraints,
)

internal class PreparedLazyItem(
    val request: LazyColumnPrefetchRequest,
    val content: @Composable @MosaicComposable () -> Unit,
    val handle: SubcomposeLayoutState.PrecomposedSlotHandle,
) {
    var premeasuredHeight: Int? = null
}

@Composable
internal fun RunLazyColumnPrefetch(
    state: LazyColumnPrefetchState,
    subcomposeState: SubcomposeLayoutState,
) {
    val plan = state.plan.value
    LaunchedEffect(plan) {
        plan ?: return@LaunchedEffect
        try {
            var preparedRows = 0L
            for (request in plan.requests) {
                if (preparedRows >= plan.targetRows) break
                if (!state.isScheduled(request)) break
                var prepared = state.preparedFor(request)
                if (prepared == null) {
                    // Keep each precomposition behind input-driven recomposition and layout work.
                    yield()
                    if (!state.isScheduled(request)) continue
                    val content: @Composable @MosaicComposable () -> Unit = {
                        request.provider.ItemAtLayoutIndex(request.layoutIndex)
                    }
                    val handle = subcomposeState.precompose(
                        slotId = request.key,
                        contentType = request.contentType,
                        content = content,
                    )
                    prepared = PreparedLazyItem(request, content, handle)
                    if (!state.install(prepared)) continue
                }
                val cachedHeight = prepared.premeasuredHeight
                if (cachedHeight != null) {
                    preparedRows += cachedHeight
                    continue
                }

                // Precomposition and premeasurement are independently cancellable stages.
                yield()
                if (state.preparedFor(request) !== prepared) continue
                try {
                    var itemHeight = 0L
                    repeat(prepared.handle.placeablesCount) { index ->
                        val size = prepared.handle.premeasure(index, request.constraints)
                        itemHeight += size?.height ?: 0
                    }
                    val boundedHeight = itemHeight.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    state.markPremeasured(prepared, boundedHeight)
                    preparedRows += boundedHeight
                } catch (_: Exception) {
                    state.cancel(request)
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            plan.requests.forEach(state::cancel)
        }
    }
    DisposableEffect(state) {
        onDispose(state::dispose)
    }
}
