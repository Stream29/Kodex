package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.jakewharton.mosaic.layout.Measurable
import com.jakewharton.mosaic.layout.Placeable
import com.jakewharton.mosaic.layout.clipToBounds
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.SubcomposeLayout
import com.jakewharton.mosaic.ui.SubcomposeLayoutState
import com.jakewharton.mosaic.ui.SubcomposeMeasureScope
import com.jakewharton.mosaic.ui.MosaicComposable
import com.jakewharton.mosaic.ui.unit.Constraints
import com.jakewharton.mosaic.ui.unit.constrainHeight
import com.jakewharton.mosaic.ui.unit.constrainWidth

/**
 * A vertically scrolling list which composes only the items around its measured viewport.
 *
 * The vertical axis must have a finite maximum height. Items may contain any Mosaic composables
 * and may occupy different numbers of terminal rows.
 *
 * When [reverseLayout] is true, logical item `0` is placed at the visual bottom and increasing
 * item indexes extend upward. Stable keys continue to identify the same logical items.
 *
 * @param interactionSource `null` when the caller does not observe scroll interactions.
 * @param keyboardPageSize Maps the measured viewport to the Page Up and Page Down row count.
 */
@Composable
public fun LazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    reverseLayout: Boolean = false,
    userScrollEnabled: Boolean = true,
    interactionSource: MutableScrollInteractionSource? = null,
    keyboardPageSize: (viewportSize: Int) -> Int = { it },
    content: LazyListScope.() -> Unit,
) {
    val latestContent = rememberUpdatedState(content)
    val nearestRange = remember(state) {
        derivedStateOf {
            calculateNearestItemsRange(state.firstVisibleItemIndex)
        }
    }
    val itemProviderState = remember(state, reverseLayout) {
        val intervalContent = derivedStateOf {
            LazyListScopeImpl().apply(latestContent.value).build()
        }
        derivedStateOf {
            LazyItemProvider(
                intervalContent = intervalContent.value,
                reverseLayout = reverseLayout,
                nearestRange = nearestRange.value,
            )
        }
    }
    val itemProvider = itemProviderState.value
    val subcomposeState = remember { SubcomposeLayoutState(maxSlotsToRetainForReuse = 2) }
    val prefetchState = remember(subcomposeState) { LazyColumnPrefetchState() }
    val beyondBoundsLayout = remember(state) {
        LazyColumnBeyondBoundsLayout(state)
    }
    val beyondBoundsLayoutElement = remember(beyondBoundsLayout) {
        LazyColumnBeyondBoundsLayoutElement(beyondBoundsLayout)
    }
    val bringIntoViewElement = remember(state, interactionSource) {
        LazyColumnBringIntoViewElement(state, interactionSource)
    }
    val remeasurementElement = remember(state) {
        LazyColumnRemeasurementElement(state)
    }
    beyondBoundsLayout.updateProvider(itemProvider)
    RunLazyColumnPrefetch(prefetchState, subcomposeState)
    SubcomposeLayout(
        state = subcomposeState,
        modifier = modifier
            .then(remeasurementElement)
            .scrollable(
                state = state,
                enabled = userScrollEnabled,
                interactionSource = interactionSource,
            )
            .scrollablePaging(
                state = state,
                viewportSize = { state.layoutInfo.viewportSize },
                pageSize = keyboardPageSize,
                enabled = userScrollEnabled,
                interactionSource = interactionSource,
            )
            .then(
                if (userScrollEnabled) {
                    Modifier
                        .then(beyondBoundsLayoutElement)
                        .then(bringIntoViewElement)
                } else {
                    Modifier
                }
            )
            .clipToBounds(),
    ) { constraints ->
        measureLazyColumn(
            itemProvider = itemProvider,
            state = state,
            beyondBoundsLayout = beyondBoundsLayout,
            constraints = constraints,
            reverseLayout = reverseLayout,
            prefetchState = prefetchState,
        )
    }
}

private fun SubcomposeMeasureScope.measureLazyColumn(
    itemProvider: LazyItemProvider,
    state: LazyListState,
    beyondBoundsLayout: LazyColumnBeyondBoundsLayout,
    constraints: Constraints,
    reverseLayout: Boolean,
    prefetchState: LazyColumnPrefetchState,
) = run {
    check(constraints.hasBoundedHeight) {
        "A lazy column must be measured with a finite maximum height."
    }

    val viewportHeight = constraints.maxHeight
    val itemConstraints = constraints.copy(
        minWidth = 0,
        minHeight = 0,
        maxHeight = Constraints.Infinity,
    )
    if (itemProvider.itemCount == 0) {
        val layoutInfo = LazyListLayoutInfo(
            visibleItemsInfo = emptyList(),
            viewportStartOffset = 0,
            viewportEndOffset = viewportHeight,
            totalItemsCount = 0,
        )
        state.publishEmptyMeasureResult(layoutInfo, viewportHeight)
        beyondBoundsLayout.publishMeasuredRange(
            firstIndex = 0,
            lastIndex = -1,
        )
        prefetchState.schedule(null)
        return@run layout(
            width = constraints.constrainWidth(0),
            height = constraints.constrainHeight(viewportHeight),
        ) {}
    }

    val measuredItems = mutableMapOf<Int, MeasuredLazyItem>()
    fun measureItem(layoutIndex: Int): MeasuredLazyItem = measuredItems.getOrPut(layoutIndex) {
        val itemIndex = itemProvider.itemIndexAt(layoutIndex)
        val key = itemProvider.keyAtLayoutIndex(layoutIndex)
        val prefetchedContent = prefetchState.contentFor(itemProvider, layoutIndex, key)
        val itemContent: @Composable @MosaicComposable () -> Unit = prefetchedContent ?: {
            itemProvider.ItemAtLayoutIndex(layoutIndex)
        }
        val placeables = subcompose(
            slotId = key,
            contentType = itemProvider.contentTypeAtLayoutIndex(layoutIndex),
            content = itemContent,
        ).map { measurable: Measurable ->
            measurable.measure(itemConstraints)
        }
        MeasuredLazyItem(
            index = itemIndex,
            key = itemProvider.keyAt(itemIndex),
            placeables = placeables,
        )
    }

    val lastLayoutIndex = itemProvider.itemCount - 1
    val forcedRange = beyondBoundsLayout.forcedRange?.let { range ->
        range.first.coerceIn(0, lastLayoutIndex)..range.last.coerceIn(0, lastLayoutIndex)
    }
    val request = state.resolveAnchor(itemProvider, reverseLayout)
    var requestedIndex = when (request) {
        LazyAnchorRequest.None -> error("LazyListState.resolveAnchor must return a concrete request.")
        LazyAnchorRequest.Start -> itemProvider.layoutIndexOf(0)
        LazyAnchorRequest.End -> itemProvider.layoutIndexOf(itemProvider.itemCount - 1)
        is LazyAnchorRequest.Position ->
            itemProvider.layoutIndexOf(request.index.coerceAtMost(itemProvider.itemCount - 1))
    }
    var requestedOffset = when (request) {
        is LazyAnchorRequest.Position -> request.scrollOffset
        else -> 0
    }
    val pendingScrollDelta = state.pendingScrollDelta()
    val alignToEnd =
        pendingScrollDelta == 0 &&
            ((!reverseLayout && request == LazyAnchorRequest.End) ||
                (reverseLayout && request == LazyAnchorRequest.Start))
    val overscanRows = viewportHeight.coerceAtLeast(1)
    var consumedScroll = pendingScrollDelta

    if (!alignToEnd && pendingScrollDelta < 0) {
        requestedOffset = saturatedAdd(requestedOffset, pendingScrollDelta)
        while (requestedOffset < 0 && requestedIndex > 0) {
            requestedIndex--
            requestedOffset = saturatedAdd(requestedOffset, measureItem(requestedIndex).height)
        }
        if (requestedOffset < 0) {
            consumedScroll = saturatedAdd(pendingScrollDelta, -requestedOffset)
            requestedOffset = 0
        }
    } else if (!alignToEnd && pendingScrollDelta > 0) {
        requestedOffset = saturatedAdd(requestedOffset, pendingScrollDelta)
    }

    var firstMeasuredIndex = requestedIndex
    var rowsBeforeAnchor = 0
    while (firstMeasuredIndex > 0 && rowsBeforeAnchor < overscanRows) {
        firstMeasuredIndex--
        rowsBeforeAnchor = saturatedAdd(
            rowsBeforeAnchor,
            measureItem(firstMeasuredIndex).height,
        )
    }
    firstMeasuredIndex = minOf(firstMeasuredIndex, forcedRange?.first ?: requestedIndex)

    for (index in firstMeasuredIndex..requestedIndex) {
        measureItem(index)
    }

    if (!alignToEnd) {
        while (true) {
            val itemHeight = measureItem(requestedIndex).height
            if (itemHeight > 0 && requestedOffset < itemHeight) break
            if (requestedIndex == lastLayoutIndex) {
                break
            }
            requestedOffset = (requestedOffset - itemHeight).coerceAtLeast(0)
            requestedIndex++
            measureItem(requestedIndex)
        }
    }

    var lastMeasuredIndex = measuredItems.keys.maxOrNull() ?: requestedIndex
    if (!alignToEnd) {
        var rowsAfterAnchor = measureItem(requestedIndex).height - requestedOffset
        val forcedLastIndex = forcedRange?.last ?: -1
        while (
            lastMeasuredIndex < lastLayoutIndex &&
            (rowsAfterAnchor < viewportHeight + overscanRows || lastMeasuredIndex < forcedLastIndex)
        ) {
            lastMeasuredIndex++
            rowsAfterAnchor = saturatedAdd(rowsAfterAnchor, measureItem(lastMeasuredIndex).height)
        }
    }

    if (alignToEnd) {
        lastMeasuredIndex = lastLayoutIndex
    }
    var reachesEnd = lastMeasuredIndex == lastLayoutIndex

    fun measuredHeight(from: Int, through: Int): Int {
        var result = 0
        for (index in from..through) {
            result = saturatedAdd(result, measureItem(index).height)
        }
        return result
    }

    var viewportStart = if (alignToEnd) {
        measuredHeight(firstMeasuredIndex, lastMeasuredIndex)
    } else {
        saturatedAdd(
            measuredHeight(firstMeasuredIndex, requestedIndex - 1),
            requestedOffset,
        )
    }

    while (firstMeasuredIndex > 0 && viewportStart < overscanRows) {
        firstMeasuredIndex--
        viewportStart = saturatedAdd(viewportStart, measureItem(firstMeasuredIndex).height)
    }

    var viewportStartBeforeTailClamp = viewportStart
    if (reachesEnd) {
        var totalMeasuredHeight = measuredHeight(firstMeasuredIndex, lastMeasuredIndex)
        val desiredTailWindow = saturatedAdd(viewportHeight, overscanRows)
        while (firstMeasuredIndex > 0 && totalMeasuredHeight < desiredTailWindow) {
            firstMeasuredIndex--
            val addedHeight = measureItem(firstMeasuredIndex).height
            viewportStart = saturatedAdd(viewportStart, addedHeight)
            totalMeasuredHeight = saturatedAdd(totalMeasuredHeight, addedHeight)
        }
        viewportStartBeforeTailClamp = viewportStart
        viewportStart = if (alignToEnd) {
            (totalMeasuredHeight - viewportHeight).coerceAtLeast(0)
        } else {
            viewportStart.coerceAtMost((totalMeasuredHeight - viewportHeight).coerceAtLeast(0))
        }
    }

    if (pendingScrollDelta > 0) {
        val tailCorrection = (viewportStartBeforeTailClamp - viewportStart).coerceAtLeast(0)
        consumedScroll = (pendingScrollDelta - tailCorrection).coerceIn(0, pendingScrollDelta)
    }

    if (!reachesEnd) {
        lastMeasuredIndex = measuredItems.keys.maxOrNull() ?: lastMeasuredIndex
        reachesEnd = lastMeasuredIndex == lastLayoutIndex
    }

    val measuredWindow = LazyMeasuredWindow(
        itemIndices = (firstMeasuredIndex..lastMeasuredIndex).map { layoutIndex ->
            measureItem(layoutIndex).index
        },
        keys = (firstMeasuredIndex..lastMeasuredIndex).map { index -> measureItem(index).key },
        heights = (firstMeasuredIndex..lastMeasuredIndex).map { index -> measureItem(index).height },
        viewportSize = viewportHeight,
        reachesStart = firstMeasuredIndex == 0,
        reachesEnd = reachesEnd,
    )
    val anchor = measuredWindow.anchorAt(viewportStart)
    val visibleItems = mutableListOf<LazyListItemInfo>()
    val itemOffsets = mutableMapOf<Int, Int>()
    val measuredContentHeight = measuredHeight(firstMeasuredIndex, lastMeasuredIndex)
    val contentStartOffset = if (
        reverseLayout &&
        firstMeasuredIndex == 0 &&
        reachesEnd &&
        measuredContentHeight < viewportHeight
    ) {
        viewportHeight - measuredContentHeight
    } else {
        0
    }
    var itemOffset = contentStartOffset - viewportStart
    var measuredWidth = 0
    for (index in firstMeasuredIndex..lastMeasuredIndex) {
        val item = measureItem(index)
        itemOffsets[index] = itemOffset
        measuredWidth = maxOf(measuredWidth, item.width)
        if (item.height > 0 && itemOffset < viewportHeight && itemOffset + item.height > 0) {
            visibleItems += LazyListItemInfo(
                index = item.index,
                key = item.key,
                offset = itemOffset,
                size = item.height,
            )
        }
        itemOffset = saturatedAdd(itemOffset, item.height)
    }
    beyondBoundsLayout.publishMeasuredRange(
        firstIndex = firstMeasuredIndex,
        lastIndex = lastMeasuredIndex,
    )

    val canScrollBackward = firstMeasuredIndex > 0 || viewportStart > 0
    val canScrollForward = !reachesEnd || itemOffset > viewportHeight
    state.publishMeasureResult(
        provider = itemProvider,
        anchorIndex = anchor.index,
        anchorOffset = anchor.offset,
        layoutInfo = LazyListLayoutInfo(
            visibleItemsInfo = visibleItems,
            viewportStartOffset = 0,
            viewportEndOffset = viewportHeight,
            totalItemsCount = itemProvider.itemCount,
        ),
        measuredWindow = measuredWindow,
        canScrollBackward = canScrollBackward,
        canScrollForward = canScrollForward,
        consumedScroll = consumedScroll,
    )
    val prefetchTargetRows = (viewportHeight.toLong() * PrefetchWindowViewports)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
    val prefetchLayoutIndices = calculatePrefetchLayoutIndices(
        direction = state.lastScrollDirection,
        firstMeasuredIndex = firstMeasuredIndex,
        lastMeasuredIndex = lastMeasuredIndex,
        lastLayoutIndex = lastLayoutIndex,
        targetRows = prefetchTargetRows,
    )
    prefetchState.schedule(
        if (prefetchLayoutIndices.isEmpty()) {
            null
        } else {
            LazyColumnPrefetchPlan(
                targetRows = prefetchTargetRows,
                requests = prefetchLayoutIndices.map { layoutIndex ->
                    LazyColumnPrefetchRequest(
                        provider = itemProvider,
                        layoutIndex = layoutIndex,
                        key = itemProvider.keyAtLayoutIndex(layoutIndex),
                        contentType = itemProvider.contentTypeAtLayoutIndex(layoutIndex),
                        constraints = itemConstraints,
                    )
                },
            )
        }
    )

    layout(
        width = constraints.constrainWidth(measuredWidth),
        height = constraints.constrainHeight(viewportHeight),
    ) {
        for (index in firstMeasuredIndex..lastMeasuredIndex) {
            val item = measureItem(index)
            var placeableOffset = checkNotNull(itemOffsets[index])
            for (placeable in item.placeables) {
                placeable.place(0, placeableOffset)
                placeableOffset += placeable.height
            }
        }
    }
}

private class MeasuredLazyItem(
    val index: Int,
    val key: Any,
    val placeables: List<Placeable>,
) {
    val width: Int = placeables.maxOfOrNull { placeable -> placeable.width } ?: 0
    val height: Int = placeables.fold(0) { result, placeable ->
        saturatedAdd(result, placeable.height)
    }
}

private fun saturatedAdd(left: Int, right: Int): Int =
    (left.toLong() + right).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

private fun calculatePrefetchLayoutIndices(
    direction: Int,
    firstMeasuredIndex: Int,
    lastMeasuredIndex: Int,
    lastLayoutIndex: Int,
    targetRows: Int,
): List<Int> {
    if (direction == 0 || targetRows <= 0) return emptyList()
    val edgeIndex = if (direction > 0) lastMeasuredIndex else firstMeasuredIndex
    val firstPrefetchIndex = edgeIndex + direction
    if (firstPrefetchIndex !in 0..lastLayoutIndex) return emptyList()
    val availableItems = if (direction > 0) {
        lastLayoutIndex - firstPrefetchIndex + 1
    } else {
        firstPrefetchIndex + 1
    }

    return List(minOf(targetRows, availableItems)) { offset ->
        firstPrefetchIndex + offset * direction
    }
}

private fun calculateNearestItemsRange(firstVisibleItemIndex: Int): IntRange {
    val windowStart =
        firstVisibleItemIndex / NearestItemsSlidingWindowSize * NearestItemsSlidingWindowSize
    val start = (windowStart - NearestItemsExtraItemCount).coerceAtLeast(0)
    val end = (windowStart.toLong() +
        NearestItemsSlidingWindowSize +
        NearestItemsExtraItemCount)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
    return start..end
}

private const val NearestItemsSlidingWindowSize = 30
private const val NearestItemsExtraItemCount = 100
private const val PrefetchWindowViewports = 2
