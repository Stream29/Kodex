package io.github.stream29.kodex.cli.components

import com.jakewharton.mosaic.layout.BeyondBoundsLayout
import com.jakewharton.mosaic.layout.BeyondBoundsLayoutProviderModifierNode
import com.jakewharton.mosaic.layout.Remeasurement
import com.jakewharton.mosaic.layout.RemeasurementModifier
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.node.ModifierNodeElement

/**
 * @property forcedRange `null` means that no active request needs items outside the ordinary
 * measured window.
 */
internal class LazyColumnBeyondBoundsLayout(
    private val state: LazyListState,
) : BeyondBoundsLayout {
    private val activeIntervals = mutableListOf<Interval>()
    private var itemCount = 0
    private var measuredFirstIndex = 0
    private var measuredLastIndex = -1

    /**
     * Initialized when this provider's modifier is attached, before focus can reach a descendant.
     */
    private lateinit var remeasurement: Remeasurement

    val forcedRange: IntRange?
        get() {
            if (activeIntervals.isEmpty()) return null
            return activeIntervals.minOf(Interval::start)..activeIntervals.maxOf(Interval::end)
        }

    fun updateProvider(provider: LazyItemProvider) {
        itemCount = provider.itemCount
    }

    fun publishMeasuredRange(
        firstIndex: Int,
        lastIndex: Int,
    ) {
        measuredFirstIndex = firstIndex
        measuredLastIndex = lastIndex
    }

    fun onRemeasurementAvailable(remeasurement: Remeasurement) {
        this.remeasurement = remeasurement
    }

    override fun <T> layout(
        direction: BeyondBoundsLayout.LayoutDirection,
        block: BeyondBoundsLayout.BeyondBoundsScope.() -> T?,
    ): T? {
        val forward = when (direction) {
            BeyondBoundsLayout.LayoutDirection.After,
            BeyondBoundsLayout.LayoutDirection.Below,
            -> true

            BeyondBoundsLayout.LayoutDirection.Before,
            BeyondBoundsLayout.LayoutDirection.Above,
            -> false

            BeyondBoundsLayout.LayoutDirection.Left,
            BeyondBoundsLayout.LayoutDirection.Right,
            -> return null

            else -> error("Unsupported beyond-bounds layout direction: $direction")
        }
        if (
            itemCount == 0 ||
            measuredLastIndex < measuredFirstIndex ||
            state.layoutInfo.visibleItemsInfo.isEmpty() ||
            !::remeasurement.isInitialized
        ) {
            return block(EmptyBeyondBoundsScope)
        }

        val startIndex = if (forward) measuredLastIndex else measuredFirstIndex
        val interval = Interval(startIndex, startIndex)
        activeIntervals += interval
        return try {
            var result: T? = null
            val maximumItemsToLayout = minOf(
                itemCount,
                BEYOND_BOUNDS_VIEWPORT_FACTOR * itemsPerViewport(),
            )
            var itemsLaidOut = 0
            while (
                result == null &&
                interval.hasMoreContent(forward) &&
                itemsLaidOut < maximumItemsToLayout
            ) {
                if (forward) {
                    interval.end++
                } else {
                    interval.start--
                }
                itemsLaidOut++
                remeasurement.forceRemeasure()
                result = block(
                    object : BeyondBoundsLayout.BeyondBoundsScope {
                        override val hasMoreContent: Boolean
                            get() = interval.hasMoreContent(forward)
                    }
                )
            }

            result
        } finally {
            activeIntervals.remove(interval)
            remeasurement.forceRemeasure()
        }
    }

    private fun Interval.hasMoreContent(forward: Boolean): Boolean =
        if (forward) end < itemCount - 1 else start > 0

    private fun itemsPerViewport(): Int {
        val visibleItems = state.layoutInfo.visibleItemsInfo
        val averageItemSize = visibleItems.sumOf(LazyListItemInfo::size) / visibleItems.size
        if (averageItemSize == 0) return 1
        return (state.layoutInfo.viewportSize / averageItemSize).coerceAtLeast(1)
    }

    private class Interval(
        var start: Int,
        var end: Int,
    )

    private companion object {
        const val BEYOND_BOUNDS_VIEWPORT_FACTOR = 2

        val EmptyBeyondBoundsScope = object : BeyondBoundsLayout.BeyondBoundsScope {
            override val hasMoreContent: Boolean = false
        }
    }
}

internal data class LazyColumnBeyondBoundsLayoutElement(
    private val layout: LazyColumnBeyondBoundsLayout,
) : ModifierNodeElement<LazyColumnBeyondBoundsLayoutNode>(),
    RemeasurementModifier {
    override fun create(): LazyColumnBeyondBoundsLayoutNode =
        LazyColumnBeyondBoundsLayoutNode(layout)

    override fun update(node: LazyColumnBeyondBoundsLayoutNode) {
        node.layout = layout
    }

    override fun onRemeasurementAvailable(remeasurement: Remeasurement) {
        layout.onRemeasurementAvailable(remeasurement)
    }
}

internal class LazyColumnBeyondBoundsLayoutNode(
    var layout: LazyColumnBeyondBoundsLayout,
) : Modifier.Node(),
    BeyondBoundsLayoutProviderModifierNode {
    override val beyondBoundsLayout: BeyondBoundsLayout
        get() = layout
}
