package io.github.stream29.kodex.cli.components

import com.jakewharton.mosaic.layout.BringIntoViewModifierNode
import com.jakewharton.mosaic.layout.LayoutCoordinates
import com.jakewharton.mosaic.layout.bringIntoView
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.node.ModifierNodeElement
import com.jakewharton.mosaic.node.requireLayoutCoordinates
import com.jakewharton.mosaic.ui.unit.IntRect
import kotlin.math.abs
import kotlinx.coroutines.CoroutineStart.UNDISPATCHED
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * @property interactionSource `null` means that focus relocation scrolls are not observed.
 */
internal data class LazyColumnBringIntoViewElement(
    private val state: LazyListState,
    private val interactionSource: MutableScrollInteractionSource?,
) : ModifierNodeElement<LazyColumnBringIntoViewNode>() {
    override fun create(): LazyColumnBringIntoViewNode =
        LazyColumnBringIntoViewNode(this)

    override fun update(node: LazyColumnBringIntoViewNode) {
        node.element = this
    }

    fun bringChildIntoView(
        childCoordinates: LayoutCoordinates,
        boundsProvider: () -> IntRect?,
        containerCoordinates: LayoutCoordinates,
    ) {
        if (!childCoordinates.isAttached || !containerCoordinates.isAttached) return
        val bounds = boundsProvider() ?: return
        val childOffset = childCoordinates.position - containerCoordinates.position
        val localBounds = bounds.translate(childOffset)
        val requestedDelta = calculateScrollDistance(
            offset = localBounds.top,
            size = localBounds.height,
            containerSize = containerCoordinates.size.height,
        )
        if (requestedDelta == 0) return

        val consumedDelta = state.scrollBy(requestedDelta)
        if (consumedDelta == 0) return
        interactionSource?.tryEmit(
            ScrollInteraction(
                source = ScrollInputSource.FocusRelocation,
                orientation = ScrollOrientation.Vertical,
                requestedDelta = requestedDelta,
                consumedDelta = consumedDelta,
            )
        )
        state.forceRemeasureIfNeeded()
    }
}

internal class LazyColumnBringIntoViewNode(
    var element: LazyColumnBringIntoViewElement,
) : Modifier.Node(),
    BringIntoViewModifierNode {
    override suspend fun bringIntoView(
        childCoordinates: LayoutCoordinates,
        boundsProvider: () -> IntRect?,
    ) {
        fun boundsForParent(): IntRect? {
            if (!isAttached || !childCoordinates.isAttached) return null
            val containerCoordinates = requireLayoutCoordinates()
            return boundsProvider()
                ?.translate(childCoordinates.position - containerCoordinates.position)
        }

        coroutineScope {
            // Focus commits synchronously, so both branches must start before control returns.
            launch(start = UNDISPATCHED) {
                element.bringChildIntoView(
                    childCoordinates = childCoordinates,
                    boundsProvider = boundsProvider,
                    containerCoordinates = requireLayoutCoordinates(),
                )
            }
            launch(start = UNDISPATCHED) {
                bringIntoView(::boundsForParent)
            }
        }
    }
}

private fun calculateScrollDistance(
    offset: Int,
    size: Int,
    containerSize: Int,
): Int {
    val leadingEdge = offset.toLong()
    val trailingEdge = leadingEdge + size
    val containerEnd = containerSize.toLong()
    val distance = when {
        leadingEdge >= 0 && trailingEdge <= containerEnd -> 0
        leadingEdge < 0 && trailingEdge > containerEnd -> 0
        abs(leadingEdge) < abs(trailingEdge - containerEnd) -> leadingEdge
        else -> trailingEdge - containerEnd
    }
    return distance.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
}
