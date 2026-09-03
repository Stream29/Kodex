package io.github.stream29.kodex.cli.components

import com.jakewharton.mosaic.layout.BringIntoViewModifierNode
import com.jakewharton.mosaic.layout.LayoutCoordinates
import com.jakewharton.mosaic.layout.Remeasurement
import com.jakewharton.mosaic.layout.RemeasurementModifier
import com.jakewharton.mosaic.layout.bringIntoView
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.node.ModifierNodeElement
import com.jakewharton.mosaic.node.requireLayoutCoordinates
import com.jakewharton.mosaic.ui.unit.IntRect
import kotlin.math.abs
import kotlinx.coroutines.CoroutineStart.UNDISPATCHED
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal data class VerticalScrollBringIntoViewElement(
    private val state: ScrollState,
    private val reverseScrolling: Boolean,
    private val interactionSource: MutableScrollInteractionSource?,
) : ModifierNodeElement<VerticalScrollBringIntoViewNode>(), RemeasurementModifier {
    private var node: VerticalScrollBringIntoViewNode? = null
    private var remeasurement: Remeasurement? = null

    override fun create(): VerticalScrollBringIntoViewNode =
        VerticalScrollBringIntoViewNode(this).also { node ->
            this.node = node
            remeasurement?.let(node::updateRemeasurement)
        }

    override fun onRemeasurementAvailable(remeasurement: Remeasurement) {
        this.remeasurement = remeasurement
        node?.updateRemeasurement(remeasurement)
    }

    override fun update(node: VerticalScrollBringIntoViewNode) {
        this.node = node
        node.element = this
        remeasurement?.let(node::updateRemeasurement)
    }

    fun bringChildIntoView(
        childCoordinates: LayoutCoordinates,
        boundsProvider: () -> IntRect?,
        containerCoordinates: LayoutCoordinates,
    ): Boolean {
        if (!childCoordinates.isAttached || !containerCoordinates.isAttached) return false
        val bounds = boundsProvider() ?: return false
        val childOffset = childCoordinates.position - containerCoordinates.position
        val localBounds = bounds.translate(childOffset)
        val requestedDelta = calculateScrollDistance(
            offset = localBounds.top,
            size = localBounds.height,
            containerSize = containerCoordinates.size.height,
        )
        if (requestedDelta == 0) return false

        val stateDelta = if (reverseScrolling) -requestedDelta else requestedDelta
        val consumedDelta = state.scrollBy(stateDelta)
        if (consumedDelta == 0) return false
        interactionSource?.tryEmit(
            ScrollInteraction(
                source = ScrollInputSource.FocusRelocation,
                orientation = ScrollOrientation.Vertical,
                requestedDelta = stateDelta,
                consumedDelta = consumedDelta,
            )
        )
        return true
    }
}

internal class VerticalScrollBringIntoViewNode(
    var element: VerticalScrollBringIntoViewElement,
) : Modifier.Node(),
    BringIntoViewModifierNode {
    private var remeasurement: Remeasurement? = null

    fun updateRemeasurement(remeasurement: Remeasurement) {
        this.remeasurement = remeasurement
    }

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
                val childWasScrolled = element.bringChildIntoView(
                    childCoordinates = childCoordinates,
                    boundsProvider = boundsProvider,
                    containerCoordinates = requireLayoutCoordinates(),
                )
                if (childWasScrolled) {
                    remeasurement?.forceRemeasure()
                }
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
