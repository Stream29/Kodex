package io.github.stream29.codex.lite.cli.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/** Geometry of one currently visible lazy item, measured in terminal rows. */
@Immutable
public data class LazyListItemInfo(
    public val index: Int,
    public val key: Any,
    public val offset: Int,
    public val size: Int,
)

/** Immutable projection of the latest completed [LazyColumn] measure pass. */
@Immutable
public data class LazyListLayoutInfo(
    public val visibleItemsInfo: List<LazyListItemInfo>,
    public val viewportStartOffset: Int,
    public val viewportEndOffset: Int,
    public val totalItemsCount: Int,
) {
    public val viewportSize: Int
        get() = viewportEndOffset - viewportStartOffset

    internal companion object {
        val Empty = LazyListLayoutInfo(
            visibleItemsInfo = emptyList(),
            viewportStartOffset = 0,
            viewportEndOffset = 0,
            totalItemsCount = 0,
        )
    }
}

/** Scroll position and measured viewport state owned independently of one [LazyColumn] call. */
@Stable
public class LazyListState(
    initialFirstVisibleItemIndex: Int = 0,
    initialFirstVisibleItemScrollOffset: Int = 0,
) : ScrollableState {
    private val firstVisibleItemIndexState = mutableIntStateOf(initialFirstVisibleItemIndex)
    private val firstVisibleItemScrollOffsetState = mutableIntStateOf(initialFirstVisibleItemScrollOffset)
    private val layoutInfoState = mutableStateOf(LazyListLayoutInfo.Empty)
    private val canScrollBackwardState = mutableStateOf(
        initialFirstVisibleItemIndex > 0 || initialFirstVisibleItemScrollOffset > 0
    )
    private val canScrollForwardState = mutableStateOf(true)
    private val scrollInProgressState = mutableStateOf(false)
    private val requestedAnchorState = mutableStateOf<LazyAnchorRequest>(LazyAnchorRequest.None)
    private var anchorKey: Any = NoAnchorKey

    /** `null` until a measure pass has published a contiguous window around the current anchor. */
    private var measuredWindow: LazyMeasuredWindow? = null

    init {
        require(initialFirstVisibleItemIndex >= 0) { "Initial lazy list index cannot be negative." }
        require(initialFirstVisibleItemScrollOffset >= 0) {
            "Initial lazy list scroll offset cannot be negative."
        }
    }

    public val firstVisibleItemIndex: Int
        get() = firstVisibleItemIndexState.intValue

    public val firstVisibleItemScrollOffset: Int
        get() = firstVisibleItemScrollOffsetState.intValue

    public val layoutInfo: LazyListLayoutInfo
        get() = layoutInfoState.value

    override val canScrollBackward: Boolean
        get() = canScrollBackwardState.value

    override val canScrollForward: Boolean
        get() = canScrollForwardState.value

    override val isScrollInProgress: Boolean
        get() = scrollInProgressState.value

    override fun scrollBy(delta: Int): Int {
        if (delta == 0) return 0
        val window = measuredWindow ?: return 0
        val currentPosition = window.positionOf(
            index = firstVisibleItemIndex,
            offset = firstVisibleItemScrollOffset,
        ) ?: return 0
        val maximumPosition = if (window.reachesEnd) {
            (window.totalHeight - window.viewportSize).coerceAtLeast(0)
        } else {
            (window.totalHeight - window.viewportSize).coerceAtLeast(currentPosition)
        }
        val targetPosition = (currentPosition.toLong() + delta)
            .coerceIn(0L, maximumPosition.toLong())
            .toInt()
        val consumed = targetPosition - currentPosition
        if (consumed == 0) return 0

        scrollInProgressState.value = true
        return try {
            val anchor = window.anchorAt(targetPosition)
            requestedAnchorState.value = LazyAnchorRequest.None
            firstVisibleItemIndexState.intValue = anchor.index
            firstVisibleItemScrollOffsetState.intValue = anchor.offset
            anchorKey = anchor.key
            canScrollBackwardState.value = anchor.index > 0 || anchor.offset > 0
            canScrollForwardState.value = !window.reachesEnd || targetPosition < maximumPosition
            consumed
        } finally {
            scrollInProgressState.value = false
        }
    }

    /** Requests [index] and [scrollOffset] as the next measured anchor. */
    public fun scrollToItem(index: Int, scrollOffset: Int = 0) {
        require(index >= 0) { "Lazy list index cannot be negative." }
        require(scrollOffset >= 0) { "Lazy list scroll offset cannot be negative." }
        requestedAnchorState.value = LazyAnchorRequest.Position(index, scrollOffset)
    }

    /** Requests the logical start as the next measured position. */
    public fun requestScrollToStart() {
        requestedAnchorState.value = LazyAnchorRequest.Start
    }

    /** Requests the logical end as the next measured position. */
    public fun requestScrollToEnd() {
        requestedAnchorState.value = LazyAnchorRequest.End
    }

    internal fun resolveAnchor(provider: LazyItemProvider): LazyAnchorRequest {
        val request = requestedAnchorState.value
        if (request != LazyAnchorRequest.None) return request
        val currentIndex = firstVisibleItemIndex
        val currentOffset = firstVisibleItemScrollOffset
        val restoredIndex = if (anchorKey === NoAnchorKey) null else provider.indexOfKey(anchorKey)
        return LazyAnchorRequest.Position(
            index = restoredIndex ?: currentIndex,
            scrollOffset = currentOffset,
        )
    }

    internal fun publishMeasureResult(
        provider: LazyItemProvider,
        anchorIndex: Int,
        anchorOffset: Int,
        layoutInfo: LazyListLayoutInfo,
        measuredWindow: LazyMeasuredWindow,
        canScrollBackward: Boolean,
        canScrollForward: Boolean,
    ) {
        firstVisibleItemIndexState.intValue = anchorIndex
        firstVisibleItemScrollOffsetState.intValue = anchorOffset
        layoutInfoState.value = layoutInfo
        this.measuredWindow = measuredWindow
        canScrollBackwardState.value = canScrollBackward
        canScrollForwardState.value = canScrollForward
        anchorKey = if (provider.itemCount == 0) NoAnchorKey else provider.keyAt(anchorIndex)
        requestedAnchorState.value = LazyAnchorRequest.None
    }

    internal fun publishEmptyMeasureResult(
        layoutInfo: LazyListLayoutInfo,
        viewportSize: Int,
    ) {
        firstVisibleItemIndexState.intValue = 0
        firstVisibleItemScrollOffsetState.intValue = 0
        layoutInfoState.value = layoutInfo
        measuredWindow = LazyMeasuredWindow(
            firstIndex = 0,
            keys = emptyList(),
            heights = emptyList(),
            viewportSize = viewportSize,
            reachesEnd = true,
        )
        canScrollBackwardState.value = false
        canScrollForwardState.value = false
        anchorKey = NoAnchorKey
        requestedAnchorState.value = LazyAnchorRequest.None
    }
}

/** Remembers one [LazyListState] across recompositions. */
@Composable
public fun rememberLazyListState(
    initialFirstVisibleItemIndex: Int = 0,
    initialFirstVisibleItemScrollOffset: Int = 0,
): LazyListState = remember {
    LazyListState(initialFirstVisibleItemIndex, initialFirstVisibleItemScrollOffset)
}

internal sealed interface LazyAnchorRequest {
    data object None : LazyAnchorRequest
    data object Start : LazyAnchorRequest
    data object End : LazyAnchorRequest
    data class Position(val index: Int, val scrollOffset: Int) : LazyAnchorRequest
}

internal data class LazyMeasuredWindow(
    val firstIndex: Int,
    val keys: List<Any>,
    val heights: List<Int>,
    val viewportSize: Int,
    val reachesEnd: Boolean,
) {
    val totalHeight: Int = heights.sum()

    fun positionOf(index: Int, offset: Int): Int? {
        val localIndex = index - firstIndex
        if (localIndex !in heights.indices) return null
        var position = offset
        for (itemIndex in 0 until localIndex) {
            position += heights[itemIndex]
        }
        return position
    }

    fun anchorAt(position: Int): LazyWindowAnchor {
        var remaining = position
        for (localIndex in heights.indices) {
            val height = heights[localIndex]
            if (height > 0 && remaining < height) {
                return LazyWindowAnchor(
                    index = firstIndex + localIndex,
                    offset = remaining,
                    key = keys[localIndex],
                )
            }
            remaining -= height
        }
        val fallbackIndex = heights.indexOfLast { it > 0 }.coerceAtLeast(0)
        return LazyWindowAnchor(
            index = firstIndex + fallbackIndex,
            offset = (heights[fallbackIndex] - 1).coerceAtLeast(0),
            key = keys[fallbackIndex],
        )
    }
}

internal data class LazyWindowAnchor(
    val index: Int,
    val offset: Int,
    val key: Any,
)

private object NoAnchorKey
