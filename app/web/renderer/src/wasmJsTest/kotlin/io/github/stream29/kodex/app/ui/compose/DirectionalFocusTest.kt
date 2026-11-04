@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.skiko.wasm.onWasmReady

private val directionalPopupPosition = object : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize,
    ) = IntOffset(20, 20)
}

@Composable
private fun DirectionalRows(consume: Boolean, actions: MutableList<String>) {
    for (row in 0..1) Row {
        for (column in 0..1) {
            val label = "$row:$column"
            TextButton(onClick = { actions += label }, modifier = Modifier.onKeyEvent {
                if (consume && it.type == KeyEventType.KeyDown && it.key == Key.DirectionRight) {
                    actions += "child"; true
                } else false
            }) { Text(label) }
        }
    }
}

private suspend fun directionalScene(layer: String, consume: Boolean = false, actions: MutableList<String>) =
    DirectionalScene.create {
        when (layer) {
            "root" -> Column { DirectionalRows(consume, actions) }
            "dialog" -> EditorDialog({}) { Column { DirectionalRows(consume, actions) } }
            "popup" -> EditorPopup(directionalPopupPosition, properties = PopupProperties(focusable = true)) {
                Column { DirectionalRows(consume, actions) }
            }
            "dropdown" -> EditorDropdownMenu(true, {}) { DirectionalRows(consume, actions) }
        }
    }

private fun DirectionalScene.directionalNode(label: String) = nodes().first { node ->
    node.config.getOrNull(SemanticsActions.RequestFocus) != null && node.children.any { child ->
        child.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
    }
}

private suspend fun DirectionalScene.focusDirectional(label: String) {
    assertTrue(assertNotNull(directionalNode(label).config.getOrNull(SemanticsActions.RequestFocus)?.action).invoke())
    settle { directionalNode(label).config.getOrNull(SemanticsProperties.Focused) == true }
}

val directionalFocusTest by testSuite {
    for (layer in listOf("root", "dialog", "popup", "dropdown")) {
        test("$layer navigates all four directions with existing focus search") { withContext(Dispatchers.Default) {
            val actions = mutableListOf<String>()
            val scene = directionalScene(layer, actions = actions)
            try {
                scene.settle { scene.hasText("1:1") }
                scene.focusDirectional("0:0")
                for ((key, label) in listOf(
                    Key.DirectionRight to "0:1", Key.DirectionDown to "1:1",
                    Key.DirectionLeft to "1:0", Key.DirectionUp to "0:0",
                )) {
                    scene.key(key)
                    scene.settle { scene.directionalNode(label).config.getOrNull(SemanticsProperties.Focused) == true }
                    scene.key(Key.Spacebar)
                    scene.settle { actions.lastOrNull() == label }
                }
                assertEquals(listOf("0:1", "1:1", "1:0", "0:0"), actions)
                assertTrue(scene.directionalNode("1:0").boundsInWindow.top >
                    scene.directionalNode("0:0").boundsInWindow.top)
            } finally { scene.close() }
        } }
        test("$layer gives child handlers priority") { withContext(Dispatchers.Default) {
            val actions = mutableListOf<String>()
            val scene = directionalScene(layer, consume = true, actions = actions)
            try {
                scene.settle { scene.hasText("1:1") }
                scene.focusDirectional("0:0")
                scene.key(Key.DirectionRight)
                scene.key(Key.Spacebar)
                scene.settle { actions.size == 2 }
                assertEquals(listOf("child", "0:0"), actions)
            } finally { scene.close() }
        } }
        test("$layer ignores Shift and key up and preserves owner edge handling") { withContext(Dispatchers.Default) {
            val actions = mutableListOf<String>()
            val scene = directionalScene(layer, actions = actions)
            try {
                scene.settle { scene.hasText("1:1") }
                scene.focusDirectional("0:0")
                assertFalse(scene.scene.sendKeyEvent(KeyEvent(Key.DirectionRight, KeyEventType.KeyDown, isShiftPressed = true)))
                assertFalse(scene.scene.sendKeyEvent(KeyEvent(Key.DirectionRight, KeyEventType.KeyUp)))
                assertFalse(scene.scene.sendKeyEvent(KeyEvent(Key.DirectionLeft, KeyEventType.KeyDown)))
                val upConsumed = scene.scene.sendKeyEvent(KeyEvent(Key.DirectionUp, KeyEventType.KeyDown))
                if (layer == "dropdown") {
                    assertTrue(upConsumed, "Material dropdown retains its Previous traversal at the upper edge")
                    scene.settle { scene.directionalNode("1:1").config.getOrNull(SemanticsProperties.Focused) == true }
                    scene.focusDirectional("0:0")
                } else {
                    assertFalse(upConsumed, "Missing spatial targets leave the key unconsumed")
                }
                scene.key(Key.Spacebar)
                scene.settle { actions.isNotEmpty() }
                assertEquals(listOf("0:0"), actions)
            } finally { scene.close() }
        } }
    }
}

private class DirectionalScene(val scene: ImageComposeScene) : AutoCloseable {
    private var frame = 0L

    fun nodes(): List<SemanticsNode> = buildList {
        fun visit(node: SemanticsNode) {
            add(node)
            node.children.forEach(::visit)
        }
        scene.semanticsOwners.forEach { visit(it.unmergedRootSemanticsNode) }
    }

    fun hasText(label: String): Boolean = nodes().any {
        it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true
    }

    suspend fun settle(until: () -> Boolean) {
        withTimeout(10_000) {
            do {
                scene.render(++frame * 16_666_667).close()
                delay(1)
            } while (!until() || scene.hasInvalidations())
        }
    }

    fun key(key: Key) {
        scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown))
        scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
    }

    override fun close() { scene.close() }

    companion object {
        suspend fun create(content: @Composable () -> Unit): DirectionalScene {
            val ready = CompletableDeferred<Unit>()
            onWasmReady { ready.complete(Unit) }
            withTimeout(10_000) { ready.await() }
            val scene = ImageComposeScene(800, 900, coroutineContext = Dispatchers.Unconfined)
            try {
                scene.setContent { MaterialTheme { DirectionalFocusBoundary(content) } }
            } catch (failure: Throwable) {
                scene.close()
                throw failure
            }
            return DirectionalScene(scene)
        }
    }
}
