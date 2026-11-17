package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull

internal fun OffscreenScene.overlayTextFocused(label: String): Boolean {
    fun contains(node: SemanticsNode): Boolean =
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
            node.children.any(::contains)
    return nodes().any { it.config.getOrNull(SemanticsProperties.Focused) == true && contains(it) }
}
