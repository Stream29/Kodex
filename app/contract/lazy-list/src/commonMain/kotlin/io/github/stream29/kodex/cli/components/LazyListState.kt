package io.github.stream29.kodex.cli.components

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

/** Immutable projection of the latest completed `LazyColumn` measure pass. */
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

/** Scroll position and measured viewport state owned independently of one `LazyColumn` call. */
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
    private var hasMeasuredItems: Boolean = false
    private var remeasurement: LazyListRemeasurement? = null
    private var scrollToBeConsumed: Int = 0
    private var needsRemeasureAfterScroll: Boolean = false
    internal var lastScrollDirection: Int = 0
        private set

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
        if (delta < 0 && !canScrollBackward || delta > 0 && !canScrollForward) return 0
        val previousScrollDirection = lastScrollDirection
        lastScrollDirection = if (delta > 0) 1 else -1
        scrollInProgressState.value = true
        return try {
            val consumed = tryScrollWithoutRemeasure(delta) ?: scrollWithRemeasure(delta)
            if (consumed == 0) {
                lastScrollDirection = previousScrollDirection
            }
            consumed
        } finally {
            scrollInProgressState.value = false
        }
    }

    private fun tryScrollWithoutRemeasure(delta: Int): Int? {
        val window = measuredWindow ?: return null
        val currentPosition = window.positionOf(
            index = firstVisibleItemIndex,
            offset = firstVisibleItemScrollOffset,
        ) ?: return null
        val targetPositionLong = currentPosition.toLong() + delta
        if (targetPositionLong !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return null
        val targetPosition = targetPositionLong.toInt()
        val maximumPosition = (window.totalHeight - window.viewportSize).coerceAtLeast(0)
        if (targetPosition < 0 || targetPosition > maximumPosition) return null

        val anchor = window.anchorAt(targetPosition)
        requestedAnchorState.value = LazyAnchorRequest.None
        firstVisibleItemIndexState.intValue = anchor.index
        firstVisibleItemScrollOffsetState.intValue = anchor.offset
        anchorKey = anchor.key
        canScrollBackwardState.value = !window.reachesStart || targetPosition > 0
        canScrollForwardState.value = !window.reachesEnd || targetPosition < maximumPosition
        needsRemeasureAfterScroll = true
        return delta
    }

    private fun scrollWithRemeasure(delta: Int): Int {
        val remeasurement = remeasurement ?: return 0
        check(scrollToBeConsumed == 0) {
            "Lazy list cannot start a scroll while another scroll delta is pending."
        }
        scrollToBeConsumed = delta
        return try {
            remeasurement.forceRemeasure()
            val unconsumed = scrollToBeConsumed
            scrollToBeConsumed = 0
            delta - unconsumed
        } catch (throwable: Throwable) {
            scrollToBeConsumed = 0
            throw throwable
        }
    }

    /** Requests [index] and [scrollOffset] as the next measured anchor. */
    public fun scrollToItem(index: Int, scrollOffset: Int = 0) {
        require(index >= 0) { "Lazy list index cannot be negative." }
        require(scrollOffset >= 0) { "Lazy list scroll offset cannot be negative." }
        scrollToBeConsumed = 0
        lastScrollDirection = 0
        requestedAnchorState.value = LazyAnchorRequest.Position(index, scrollOffset)
    }

    /** Requests the logical start as the next measured position. */
    public fun requestScrollToStart() {
        scrollToBeConsumed = 0
        lastScrollDirection = 0
        requestedAnchorState.value = LazyAnchorRequest.Start
    }

    /** Requests the logical end as the next measured position. */
    public fun requestScrollToEnd() {
        scrollToBeConsumed = 0
        lastScrollDirection = 0
        requestedAnchorState.value = LazyAnchorRequest.End
    }

    internal fun attachRemeasurement(remeasurement: LazyListRemeasurement) {
        check(this.remeasurement == null || this.remeasurement === remeasurement) {
            "A LazyListState cannot be attached to multiple LazyColumns."
        }
        this.remeasurement = remeasurement
    }

    internal fun detachRemeasurement(remeasurement: LazyListRemeasurement) {
        if (this.remeasurement === remeasurement) {
            this.remeasurement = null
            scrollToBeConsumed = 0
            needsRemeasureAfterScroll = false
        }
    }

    internal fun pendingScrollDelta(): Int = scrollToBeConsumed

    internal fun forceRemeasureIfNeeded() {
        if (needsRemeasureAfterScroll) {
            remeasurement?.forceRemeasure()
        }
    }

    internal fun resolveAnchor(
        provider: LazyItemProvider,
        reverseLayout: Boolean,
    ): LazyAnchorRequest {
        val request = requestedAnchorState.value
        if (request != LazyAnchorRequest.None) return request
        val currentIndex = firstVisibleItemIndex
        val currentOffset = firstVisibleItemScrollOffset
        if (reverseLayout && !hasMeasuredItems && currentIndex == 0 && currentOffset == 0) {
            return LazyAnchorRequest.Start
        }
        val restoredIndex = if (anchorKey === NoAnchorKey) {
            null
        } else if (
            currentIndex in 0 until provider.itemCount &&
            provider.keyAt(currentIndex) == anchorKey
        ) {
            currentIndex
        } else {
            provider.indexOfKey(anchorKey)
        }
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
        consumedScroll: Int,
    ) {
        firstVisibleItemIndexState.intValue = anchorIndex
        firstVisibleItemScrollOffsetState.intValue = anchorOffset
        layoutInfoState.value = layoutInfo
        this.measuredWindow = measuredWindow
        canScrollBackwardState.value = canScrollBackward
        canScrollForwardState.value = canScrollForward
        scrollToBeConsumed -= consumedScroll
        needsRemeasureAfterScroll = false
        anchorKey = if (provider.itemCount == 0) NoAnchorKey else provider.keyAt(anchorIndex)
        hasMeasuredItems = true
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
            itemIndices = emptyList(),
            keys = emptyList(),
            heights = emptyList(),
            viewportSize = viewportSize,
            reachesStart = true,
            reachesEnd = true,
        )
        canScrollBackwardState.value = false
        canScrollForwardState.value = false
        anchorKey = NoAnchorKey
        hasMeasuredItems = false
        needsRemeasureAfterScroll = false
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
    val itemIndices: List<Int>,
    val keys: List<Any>,
    val heights: List<Int>,
    val viewportSize: Int,
    val reachesStart: Boolean,
    val reachesEnd: Boolean,
) {
    init {
        require(itemIndices.size == keys.size && keys.size == heights.size) {
            "Lazy measured-window item metadata must have matching sizes."
        }
    }

    val totalHeight: Int = heights.sum()

    fun positionOf(index: Int, offset: Int): Int? {
        val localIndex = itemIndices.indexOf(index)
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
                    index = itemIndices[localIndex],
                    offset = remaining,
                    key = keys[localIndex],
                )
            }
            remaining -= height
        }
        val fallbackIndex = heights.indexOfLast { it > 0 }.coerceAtLeast(0)
        return LazyWindowAnchor(
            index = itemIndices[fallbackIndex],
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

internal fun interface LazyListRemeasurement {
    fun forceRemeasure()
}
