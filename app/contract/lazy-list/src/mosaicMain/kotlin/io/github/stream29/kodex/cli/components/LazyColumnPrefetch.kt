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
    private val requestState = mutableStateOf<LazyColumnPrefetchRequest?>(null)
    val request: State<LazyColumnPrefetchRequest?> get() = requestState

    private var prepared: PreparedLazyItem? = null

    fun schedule(request: LazyColumnPrefetchRequest?) {
        val current = requestState.value
        if (current == request) return
        requestState.value = request
        if (request == null || prepared?.request != request) {
            prepared?.handle?.dispose()
            prepared = null
        }
    }

    fun install(item: PreparedLazyItem) {
        if (requestState.value == item.request) {
            prepared?.handle?.dispose()
            prepared = item
        } else {
            item.handle.dispose()
        }
    }

    fun contentFor(
        provider: LazyItemProvider,
        layoutIndex: Int,
        key: Any,
    ): (@Composable @MosaicComposable () -> Unit)? {
        val item = prepared ?: return null
        if (
            item.request.provider !== provider ||
            item.request.layoutIndex != layoutIndex ||
            item.request.key != key
        ) {
            return null
        }
        prepared = null
        return item.content
    }

    fun cancel(request: LazyColumnPrefetchRequest) {
        if (prepared?.request == request) {
            prepared?.handle?.dispose()
            prepared = null
        }
    }

    fun dispose() {
        requestState.value = null
        prepared?.handle?.dispose()
        prepared = null
    }
}

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
)

@Composable
internal fun RunLazyColumnPrefetch(
    state: LazyColumnPrefetchState,
    subcomposeState: SubcomposeLayoutState,
) {
    val request = state.request.value
    LaunchedEffect(request) {
        request ?: return@LaunchedEffect
        var installed = false
        try {
            // Keep prefetch behind input-driven recomposition and layout work.
            yield()
            val content: @Composable @MosaicComposable () -> Unit = {
                request.provider.ItemAtLayoutIndex(request.layoutIndex)
            }
            val handle = subcomposeState.precompose(
                slotId = request.key,
                contentType = request.contentType,
                content = content,
            )
            val prepared = PreparedLazyItem(request, content, handle)
            state.install(prepared)
            installed = true

            // Precomposition and premeasurement are independently cancellable stages.
            yield()
            if (state.request.value == request) {
                repeat(handle.placeablesCount) { index ->
                    handle.premeasure(index, request.constraints)
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            state.cancel(request)
        } finally {
            if (!installed) state.cancel(request)
        }
    }
    DisposableEffect(state) {
        onDispose(state::dispose)
    }
}
