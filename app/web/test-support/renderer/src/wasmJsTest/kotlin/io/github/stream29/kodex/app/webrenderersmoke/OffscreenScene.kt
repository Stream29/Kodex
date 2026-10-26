@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.runtime.InternalComposeApi::class)

package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.findDefaultNavigationEventDispatcherOwner
import androidx.navigationevent.DirectNavigationEventInput
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.skiko.wasm.onWasmReady
import io.github.stream29.kodex.app.ui.WithUiTextInputRenderer
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val offscreenLock = Mutex()
internal suspend fun offscreenTest(block: suspend () -> Unit): Unit =
    withContext(Dispatchers.Default) { offscreenLock.withLock { block() } }

internal class OffscreenScene private constructor(
    val scene: ImageComposeScene, private val navigation: DirectNavigationEventInput,
    private val failures: MutableList<Throwable>,
) : AutoCloseable {
    private var frame = 0L
    private fun checkFailures() {
        if (failures.isNotEmpty()) throw AssertionError("Offscreen composition failed", failures.removeAt(0))
    }
    fun advance() {
        checkFailures()
        scene.render(++frame * 16_666_667).close()
        checkFailures()
    }
    fun frame(): ByteArray {
        checkFailures()
        val image = scene.render(++frame * 16_666_667)
        try {
            checkFailures()
            val encoded = assertNotNull(image.encodeToData())
            try {
                return encoded.bytes.also {
                    assertTrue(it.size > 100)
                    assertTrue(it.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map(Int::toByte))
                }
            } finally { encoded.close() }
        } finally { image.close() }
    }

    suspend fun settle(label: String = "scene condition", until: () -> Boolean) {
        try {
            withTimeout(10_000) {
                do { advance(); delay(1) } while (
                    !until() || Snapshot.current.hasPendingChanges() ||
                        Snapshot.isApplyObserverNotificationPending || scene.hasInvalidations()
                )
            }
        } catch (failure: TimeoutCancellationException) {
            val text = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
                .joinToString(" | ") { it.text }.take(2_000)
            throw AssertionError("$label timed out; raster semantics: $text", failure)
        }
    }

    fun nodes(): List<SemanticsNode> = buildList {
        fun visit(node: SemanticsNode) {
            add(node)
            node.children.forEach(::visit)
        }
        scene.semanticsOwners.forEach { visit(it.unmergedRootSemanticsNode) }
    }

    fun semanticsOwnerCount(): Int = scene.semanticsOwners.size

    fun text(value: String): SemanticsNode = nodes().first { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true
    }

    fun hasText(value: String): Boolean = nodes().any { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true
    }

    fun click(label: String, occurrence: Int = 0) {
        val matches = nodes().filter { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
        }
        clickNode(matches[occurrence])
    }

    fun hasDescription(value: String): Boolean = nodes().any { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(value) == true
    }

    fun clickDescription(value: String) {
        clickNode(nodes().first { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(value) == true
        })
    }

    fun contextDescription(value: String) {
        val node = nodes().first {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(value) == true
        }
        contextNode(node)
    }

    fun contextText(value: String) { contextNode(text(value)) }

    private fun contextNode(node: SemanticsNode) {
        assertTrue(node.boundsInWindow.width > 0 && node.boundsInWindow.height > 0)
        scene.sendPointerEvent(
            PointerEventType.Press, node.boundsInWindow.center,
            buttons = PointerButtons(isSecondaryPressed = true), button = PointerButton.Secondary,
        )
        scene.sendPointerEvent(
            PointerEventType.Release, node.boundsInWindow.center,
            buttons = PointerButtons(), button = PointerButton.Secondary,
        )
    }

    // The pinned window host maps Escape to NavigationEventInput, not directly to scene key input.
    fun escape() { navigation.backCompleted() }

    /** Fixed-version public opt-in factory for scene input only, not an OS/window keyboard assertion. */
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    fun key(key: Key, shift: Boolean = false) {
        scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isShiftPressed = shift))
        scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isShiftPressed = shift))
    }

    private fun clickNode(node: SemanticsNode) {
        assertTrue(node.boundsInWindow.width > 0 && node.boundsInWindow.height > 0, "Click target must be measured.")
        val position = node.boundsInWindow.center
        scene.sendPointerEvent(
            PointerEventType.Press, position,
            buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary,
        )
        scene.sendPointerEvent(
            PointerEventType.Release, position,
            buttons = PointerButtons(), button = PointerButton.Primary,
        )
    }

    override fun close() {
        scene.close()
        checkFailures()
    }

    companion object {
        suspend fun create(width: Int = 800, height: Int = 900, content: @Composable () -> Unit): OffscreenScene {
            val ready = CompletableDeferred<Unit>()
            onWasmReady { ready.complete(Unit) }
            withTimeout(10_000) { ready.await() }
            val navigation = DirectNavigationEventInput()
            val failures = mutableListOf<Throwable>()
            val handler = CoroutineExceptionHandler { _, failure -> failures += failure }
            val scene = ImageComposeScene(width, height, coroutineContext = Dispatchers.Unconfined + handler)
            try {
                scene.setContent {
                    io.github.stream29.kodex.app.ui.WithBrowserComponentRenderers {
                    val dispatcher = checkNotNull(findDefaultNavigationEventDispatcherOwner()).navigationEventDispatcher
                    DisposableEffect(dispatcher, navigation) {
                        dispatcher.addInput(navigation)
                        onDispose { dispatcher.removeInput(navigation) }
                    }
                    WithUiTextInputRenderer(RasterTextInput) {
                        MaterialTheme { Column(Modifier.fillMaxSize()) { content() } }
                    }
                }
                }
            } catch (failure: Throwable) {
                scene.close()
                throw failure
            }
            return OffscreenScene(scene, navigation, failures)
        }
    }
}
