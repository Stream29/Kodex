package io.github.stream29.kodex.cli.components

import com.jakewharton.mosaic.layout.BeyondBoundsLayout
import com.jakewharton.mosaic.layout.BeyondBoundsLayoutProviderModifierNode
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.node.ModifierNodeElement

/**
 * Eager scroll containers already compose and measure every descendant. A single beyond-bounds
 * pass is therefore enough to expose clipped descendants to focus search.
 */
internal class VerticalScrollBeyondBoundsLayout : BeyondBoundsLayout {
    override fun <T> layout(
        direction: BeyondBoundsLayout.LayoutDirection,
        block: BeyondBoundsLayout.BeyondBoundsScope.() -> T?,
    ): T? = when (direction) {
        BeyondBoundsLayout.LayoutDirection.Above,
        BeyondBoundsLayout.LayoutDirection.Below,
        BeyondBoundsLayout.LayoutDirection.Before,
        BeyondBoundsLayout.LayoutDirection.After,
        -> block(EmptyBeyondBoundsScope)

        BeyondBoundsLayout.LayoutDirection.Left,
        BeyondBoundsLayout.LayoutDirection.Right,
        -> null

        else -> error("Unsupported beyond-bounds layout direction: $direction")
    }

    private companion object {
        val EmptyBeyondBoundsScope = object : BeyondBoundsLayout.BeyondBoundsScope {
            override val hasMoreContent: Boolean = false
        }
    }
}

internal data class VerticalScrollBeyondBoundsLayoutElement(
    private val layout: VerticalScrollBeyondBoundsLayout = VerticalScrollBeyondBoundsLayout(),
) : ModifierNodeElement<VerticalScrollBeyondBoundsLayoutNode>() {
    override fun create(): VerticalScrollBeyondBoundsLayoutNode =
        VerticalScrollBeyondBoundsLayoutNode(layout)

    override fun update(node: VerticalScrollBeyondBoundsLayoutNode) {
        node.layout = layout
    }
}

internal class VerticalScrollBeyondBoundsLayoutNode(
    var layout: VerticalScrollBeyondBoundsLayout,
) : Modifier.Node(),
    BeyondBoundsLayoutProviderModifierNode {
    override val beyondBoundsLayout: BeyondBoundsLayout
        get() = layout
}
