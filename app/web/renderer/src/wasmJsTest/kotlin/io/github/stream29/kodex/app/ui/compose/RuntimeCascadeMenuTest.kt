@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.ui.InternalComposeUiApi::class,
    androidx.compose.runtime.InternalComposeApi::class,
    kotlin.js.ExperimentalWasmJsInterop::class,
)

package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.findDefaultNavigationEventDispatcherOwner
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.ComposeViewport
import androidx.navigationevent.DirectNavigationEventInput
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.ui.WithUiEditorSurface
import io.github.stream29.kodex.app.ui.isUiEditorObscured
import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeMenuEntry
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.jetbrains.skiko.wasm.onWasmReady
import kotlin.test.*

private fun command(key: String, selected: Boolean = false, action: () -> Unit = {}) =
    RuntimeMenuEntry.Command(key, key, selected, action)
private fun submenu(
    key: String, selected: Boolean = false, initial: String = "", children: () -> List<RuntimeMenuEntry>,
) = RuntimeMenuEntry.Branch(key, key, selected, initial, children)

val runtimeCascadeMenuTest by testSuite {
    test("measured parent rows retain three surfaces and parent click changes branch") { cascadeScene { fixture, scene ->
        scene.settle { scene.focused("model") }
        scene.click("model")
        scene.settle { scene.focused("high") }
        val rootRow = scene.bounds("model")
        val effort = scene.bounds("low")
        assertEquals(rootRow.right, effort.left)
        assertEquals(rootRow.top, effort.top)
        scene.click("high")
        scene.settle { scene.focused("fast") }
        val high = scene.bounds("high")
        val tier = scene.bounds("default")
        assertEquals(high.right, tier.left)
        assertEquals(high.top, tier.top)
        assertTrue(scene.has("model"))
        assertTrue(scene.has("high"))
        assertEquals(0, fixture.writes)
        scene.click("first")
        scene.settle { scene.has("other") && !scene.has("fast") && !scene.has("high") }
        assertFalse(scene.selected("first"))
        assertTrue(scene.selected("model"))
        assertEquals(0, fixture.writes)
    } }

    test("transparent group gap closes without replay or trigger focus restoration") { cascadeScene { fixture, scene ->
        scene.settle { scene.focused("model") }
        scene.click("model")
        scene.settle { scene.focused("high") }
        val parent = scene.bounds("first")
        val child = scene.bounds("low")
        scene.clickAt(Offset(child.left + 12, parent.top + 12))
        scene.settle { !scene.has("model") && !fixture.obscured }
        assertEquals(1, fixture.dismissals)
        assertEquals(0, fixture.restorations)
        assertEquals(0, fixture.underlyingClicks)
    } }

    test("outside press closes group and overlay registration is released") { cascadeScene { fixture, scene ->
        scene.settle { fixture.obscured && scene.has("model") }
        scene.clickAt(Offset(950f, 20f))
        scene.settle { !fixture.obscured }
        assertEquals(1, fixture.dismissals)
        assertEquals(0, fixture.underlyingClicks)
        assertEquals(0, fixture.restorations)
    } }

    test("navigation back and keyboard return to exact parent then restore once") { cascadeScene { fixture, scene ->
        scene.settle { scene.focused("model") }
        scene.key(Key.MoveHome)
        scene.settle { scene.focused("first") }
        scene.key(Key.Tab, shift = true)
        scene.settle { scene.focused("model") }
        scene.key(Key.DirectionDown)
        scene.settle { scene.focused("first") }
        scene.key(Key.DirectionUp)
        scene.settle { scene.focused("model") }
        scene.key(Key.DirectionRight)
        scene.settle { scene.focused("high") }
        scene.key(Key.Enter)
        scene.settle { scene.focused("fast") }
        scene.navigation.backCompleted()
        scene.settle { scene.focused("high") && !scene.has("fast") }
        scene.key(Key.DirectionLeft)
        scene.settle { scene.focused("model") && !scene.has("high") }
        scene.key(Key.Escape)
        scene.settle { fixture.restorations == 1 && !fixture.obscured }
        assertEquals(1, fixture.dismissals)
        assertEquals(0, fixture.writes)
    } }

    test("anchor movement repositions measured surfaces and flips left") { cascadeScene { fixture, scene ->
        scene.settle { scene.focused("model") }
        scene.click("model")
        scene.settle { scene.focused("high") }
        val rootWidth = scene.bounds("model").width
        fixture.anchor = IntRect(850, 200, 950, 230)
        scene.settle { scene.bounds("model").left == minOf(850f, 1000f - rootWidth) }
        val root = scene.bounds("model")
        val child = scene.bounds("low")
        assertEquals(root.left, child.right)
        assertEquals(root.top, child.top)
        assertEquals(0, fixture.writes)
    } }

    test("implicit trigger bounds track layout movement and restore actual trigger focus") {
        cascadeScene(prepare = {
            implicitAnchor = true
            anchor = IntRect(40, 500, 140, 530)
        }) { fixture, scene ->
            scene.settle { scene.focused("model") }
            assertEquals(40f, scene.bounds("first").left)
            assertEquals(500f, scene.bounds("model").bottom)
            fixture.anchor = IntRect(80, 300, 180, 330)
            scene.settle { scene.bounds("first").left == 80f && scene.bounds("model").bottom == 300f }
            scene.key(Key.Escape)
            scene.settle { fixture.restorations == 1 && scene.triggerFocused() }
        }
    }

    test("wrapped labels use actual row height rather than a fixed item step") {
        val longLabel = "Long model 中文 label ".repeat(6)
        cascadeScene(prepare = {
            roots = listOf(command("first"), RuntimeMenuEntry.Branch(
                "long-model", longLabel, true, "child",
            ) { listOf(command("child")) })
        }) { fixture, scene ->
            scene.settle { scene.focused(longLabel) }
            assertTrue(scene.bounds(longLabel).height > 48f)
            scene.key(Key.DirectionRight)
            scene.settle { scene.focused("child") }
            assertEquals(scene.bounds(longLabel).top, scene.bounds("child").top)
            assertEquals(0, fixture.writes)
        }
    }

    test("short menu scrolls selected row and Home End Tab focus into view") {
        cascadeScene(width = 320, height = 140, prepare = {
            roots = List(30) { command("item$it", selected = it == 20) }
            anchor = IntRect(0, 100, 60, 120)
        }) { fixture, scene ->
            scene.settle { scene.focused("item20") && scene.bounds("item20").bottom <= 140 }
            scene.key(Key.MoveEnd)
            scene.settle { scene.focused("item29") && scene.bounds("item29").bottom <= 140 }
            scene.key(Key.Tab)
            scene.settle { scene.focused("item0") && scene.bounds("item0").top >= 0 }
            scene.key(Key.MoveHome)
            scene.settle { scene.focused("item0") }
            assertEquals(0, fixture.writes)
        }
    }

    test("wheel scroll reanchors an open child without rebuilding its branch") {
        cascadeScene(prepare = {
            roots = List(20) { index ->
                submenu("row$index", selected = index == 3) { listOf(command("child$index")) }
            }
        }) { fixture, scene ->
            scene.settle { scene.focused("row3") }
            scene.click("row3")
            scene.settle { scene.focused("child3") }
            val before = scene.bounds("child3").top
            scene.scrollAt(scene.bounds("row3").center)
            scene.settle { scene.bounds("child3").top < before }
            assertEquals(scene.bounds("row3").top, scene.bounds("child3").top)
            assertEquals(0, fixture.writes)
        }
    }

    test("extreme width keeps the staircase rather than a Back page") {
        cascadeScene(width = 110) { _, scene ->
            scene.settle { scene.focused("model") }
            scene.key(Key.DirectionRight)
            scene.settle { scene.focused("high") }
            scene.key(Key.DirectionRight)
            scene.settle { scene.focused("fast") }
            assertTrue(scene.rawBounds("low").left < 0)
            assertEquals(3, scene.menuCount())
            assertTrue(scene.has("model") && scene.has("high") && scene.has("fast"))
        }
    }

    test("latest tree command is used once and owner or disabled closes without writing") { cascadeScene { fixture, scene ->
        scene.settle { scene.focused("model") }
        scene.click("model")
        scene.settle { scene.focused("high") }
        scene.click("high")
        scene.settle { scene.focused("fast") }
        val staleAction = scene.action("fast")
        fixture.roots = listOf(submenu("model") {
            listOf(submenu("high") { listOf(command("fast") { fixture.writes += 10 }) })
        })
        scene.settle { scene.has("fast") && !scene.has("low") }
        staleAction()
        scene.key(Key.Spacebar)
        scene.settle { fixture.writes == 10 && !fixture.obscured }
        assertEquals(1, fixture.dismissals)
        fixture.expanded = true
        scene.settle { scene.has("model") }
        fixture.owner = Any()
        scene.settle { !fixture.expanded && !fixture.obscured }
        assertEquals(10, fixture.writes)
        fixture.expanded = true
        scene.settle { scene.has("model") }
        fixture.enabled = false
        scene.settle { !fixture.expanded && !fixture.obscured }
        assertEquals(10, fixture.writes)
    } }

    test("invalidated path removes descendants and cancels stale focus") { cascadeScene { fixture, scene ->
        scene.settle { scene.focused("model") }
        scene.click("model")
        scene.settle { scene.focused("high") }
        scene.click("high")
        scene.settle { scene.focused("fast") }
        val staleAction = scene.action("fast")
        fixture.roots = listOf(command("replacement"))
        scene.settle { scene.focused("replacement") && !scene.has("fast") }
        staleAction()
        assertEquals(0, fixture.writes)
        scene.key(Key.Enter)
        scene.settle { !fixture.expanded }
        assertEquals(0, fixture.writes)
    } }

    test("lost explicit trigger anchor closes without restoring or accepting old actions") { cascadeScene { fixture, scene ->
        scene.settle { scene.focused("model") }
        val staleAction = scene.action("model")
        fixture.anchor = IntRect.Zero
        scene.settle { !fixture.expanded && !fixture.obscured }
        staleAction()
        assertEquals(1, fixture.dismissals)
        assertEquals(0, fixture.restorations)
        assertEquals(0, fixture.writes)
    } }

    test("real ComposeViewport canvas uses measured rows, moves, flips and consumes gaps") {
        withContext(Dispatchers.Default) {
        cascadeSceneLock.withLock {
            val ready = CompletableDeferred<Unit>()
            onWasmReady { ready.complete(Unit) }
            withTimeout(10_000) { ready.await() }
            val host = document.createElement("div") as HTMLElement
            host.id = "runtime-cascade-viewport-test"
            host.style.cssText = "position:fixed;left:0;top:0;width:1000px;height:700px;z-index:10000"
            document.body!!.appendChild(host)
            val fixture = CascadeFixture()
            var mounted by mutableStateOf(true)
            try {
                ComposeViewport(host) { MaterialTheme { if (mounted) fixture.Content() } }
                suspend fun await(label: String, condition: () -> Boolean) {
                    try { withTimeout(10_000) { while (!condition()) delay(20) } }
                    catch (failure: TimeoutCancellationException) {
                        throw AssertionError("Viewport $label: ${cascadeDomLabels(host)}", failure)
                    }
                }
                fun element(label: String) = checkNotNull(cascadeDomElement(host, "Test: $label"))
                fun click(label: String) {
                    val rect = element(label).getBoundingClientRect()
                    cascadeDomPress(host, rect.left + rect.width / 2, rect.top + rect.height / 2)
                }
                await("root") { cascadeDomElement(host, "Test: model") != null }
                click("model")
                await("effort") { cascadeDomElement(host, "Test: high") != null }
                val rootRow = element("model").getBoundingClientRect()
                val childRow = element("low").getBoundingClientRect()
                assertEquals(rootRow.right, childRow.left, 1.0)
                assertEquals(rootRow.top, childRow.top, 1.0)
                click("high")
                await("tier") { cascadeDomElement(host, "Test: fast") != null }
                assertEquals(0, fixture.writes)
                fixture.anchor = IntRect(350, 200, 450, 230)
                await("anchor movement") { element("model").getBoundingClientRect().left == 350.0 }
                host.style.width = "500px"
                window.dispatchEvent(Event("resize"))
                await("resize flip") {
                    val root = element("model").getBoundingClientRect()
                    val child = element("low").getBoundingClientRect()
                    root.left == child.right
                }
                fixture.anchor = IntRect(700, 200, 800, 230)
                await("anchor and flip") {
                    val root = cascadeDomElement(host, "Test: model")?.getBoundingClientRect()
                    val child = cascadeDomElement(host, "Test: low")?.getBoundingClientRect()
                    root != null && child != null && root.left > child.left && root.left == child.right
                }
                // Above the vertically staggered child is transparent popup space.
                val first = element("first").getBoundingClientRect()
                val low = element("low").getBoundingClientRect()
                cascadeDomPress(host, low.left + 12, first.top + 12)
                await("gap dismiss") { !fixture.expanded && !fixture.obscured }
                assertEquals(1, fixture.dismissals)
                assertEquals(0, fixture.restorations)
                assertEquals(0, fixture.underlyingClicks)
                fixture.expanded = true
                await("reopen") { cascadeDomElement(host, "Test: model") != null }
                cascadeDomKey(host, "ArrowRight", 39)
                await("keyboard expand") { cascadeDomElement(host, "Test: high") != null }
                cascadeDomKey(host, "Escape", 27)
                await("keyboard back") { cascadeDomElement(host, "Test: high") == null }
                cascadeDomKey(host, "Escape", 27)
                await("keyboard dismiss") { fixture.restorations == 1 && !fixture.obscured }
            } finally {
                mounted = false
                // ComposeViewport has no public disposer in this pinned version. Release our
                // content and DOM now; the isolated Karma browser owns the host's remaining lifetime.
                delay(100)
                host.remove()
            }
        }
        }
    }
}

private class CascadeFixture {
    var owner by mutableStateOf<Any>(Any())
    var expanded by mutableStateOf(true)
    var enabled by mutableStateOf(true)
    var anchor by mutableStateOf(IntRect(20, 600, 120, 630))
    var roots by mutableStateOf<List<RuntimeMenuEntry>>(listOf(
        submenu("first") { listOf(command("other")) },
        submenu("model", selected = true, initial = "high") {
            listOf(
                submenu("low") { listOf(command("slow")) },
                submenu("high", selected = true, initial = "fast") {
                    listOf(command("default") { writes++ }, command("fast", true) { writes++ })
                },
            )
        },
    ))
    var writes = 0
    var dismissals = 0
    var restorations = 0
    var underlyingClicks = 0
    var obscured = false
    var implicitAnchor = false

    @Composable
    fun Content() {
        val triggerFocus = remember { FocusRequester() }
        val density = LocalDensity.current
        @Composable
        fun Menu() {
            RuntimeCascadeMenu(
                owner, "Test", roots, enabled, expanded,
                onDismiss = { dismissals++; expanded = false },
                restoreTriggerFocus = {
                    restorations++
                    if (implicitAnchor) triggerFocus.requestFocus()
                },
                anchorBoundsInWindow = if (implicitAnchor) null else anchor,
            )
        }
        WithUiEditorSurface {
            obscured = isUiEditorObscured()
            Box(Modifier.fillMaxSize()) {
                TextButton(
                    onClick = { underlyingClicks++ },
                    modifier = Modifier.fillMaxSize().semantics { contentDescription = "underneath" },
                ) { Text("underneath") }
                if (implicitAnchor) {
                    Box(Modifier.offset { IntOffset(anchor.left, anchor.top) }.size(
                        with(density) { anchor.width.toDp() }, with(density) { anchor.height.toDp() },
                    )) {
                        TextButton(
                            onClick = { expanded = true },
                            modifier = Modifier.fillMaxSize().focusRequester(triggerFocus)
                                .semantics { contentDescription = "cascade-trigger" },
                        ) { Text("trigger") }
                        Menu()
                    }
                } else Menu()
            }
        }
    }
}

private val cascadeSceneLock = Mutex()
private suspend fun cascadeScene(
    width: Int = 1000, height: Int = 700,
    prepare: CascadeFixture.() -> Unit = {},
    block: suspend (CascadeFixture, CascadeScene) -> Unit,
) = withContext(Dispatchers.Default) {
    cascadeSceneLock.withLock {
        val ready = CompletableDeferred<Unit>()
        onWasmReady { ready.complete(Unit) }
        withTimeout(10_000) { ready.await() }
        val fixture = CascadeFixture().apply(prepare)
        val scene = CascadeScene(width, height, fixture)
        try { block(fixture, scene) } finally { scene.close() }
    }
}

private class CascadeScene(width: Int, height: Int, fixture: CascadeFixture) : AutoCloseable {
    val navigation = DirectNavigationEventInput()
    private val failures = mutableListOf<Throwable>()
    private val scene = ImageComposeScene(
        width, height, coroutineContext = Dispatchers.Unconfined +
            CoroutineExceptionHandler { _, failure -> failures += failure },
    )
    private var frame = 0L
    init {
        scene.setContent {
            val dispatcher = checkNotNull(findDefaultNavigationEventDispatcherOwner()).navigationEventDispatcher
            DisposableEffect(dispatcher) {
                dispatcher.addInput(navigation)
                onDispose { dispatcher.removeInput(navigation) }
            }
            MaterialTheme { fixture.Content() }
        }
    }
    private fun nodes(): List<SemanticsNode> = buildList {
        fun visit(node: SemanticsNode) { add(node); node.children.forEach(::visit) }
        scene.semanticsOwners.forEach { visit(it.unmergedRootSemanticsNode) }
    }
    private fun node(label: String): SemanticsNode? = nodes().firstOrNull {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Test: $label") == true
    }
    fun has(label: String) = node(label) != null
    fun focused(label: String) = node(label)?.config?.getOrNull(SemanticsProperties.Focused) == true
    fun triggerFocused() = nodes().any {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("cascade-trigger") == true &&
            it.config.getOrNull(SemanticsProperties.Focused) == true
    }
    fun selected(label: String) = node(label)?.config?.getOrNull(SemanticsProperties.Selected) == true
    fun action(label: String): () -> Boolean =
        checkNotNull(node(label)?.config?.getOrNull(SemanticsActions.OnClick)?.action)
    fun bounds(label: String): Rect = checkNotNull(node(label)) { "Missing $label" }.boundsInWindow
    fun rawBounds(label: String): Rect {
        val node = checkNotNull(node(label))
        return Rect(node.positionInWindow, androidx.compose.ui.geometry.Size(node.size.width.toFloat(), node.size.height.toFloat()))
    }
    fun menuCount() = nodes().count {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Test menu") == true
    }
    fun scrollAt(point: Offset) {
        scene.sendPointerEvent(PointerEventType.Scroll, point, scrollDelta = Offset(0f, 2f))
    }
    fun click(label: String) = clickAt(bounds(label).center)
    fun clickAt(point: Offset) {
        scene.sendPointerEvent(
            PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true),
            button = PointerButton.Primary,
        )
        scene.sendPointerEvent(
            PointerEventType.Release, point, buttons = PointerButtons(), button = PointerButton.Primary,
        )
    }
    fun key(key: Key, shift: Boolean = false) {
        scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isShiftPressed = shift))
        scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isShiftPressed = shift))
    }
    suspend fun settle(until: () -> Boolean) {
        try {
            withTimeout(10_000) {
                do {
                    if (failures.isNotEmpty()) throw AssertionError("Cascade composition failed", failures.removeAt(0))
                    scene.render(++frame * 16_666_667).close()
                    delay(1)
                } while (!until() || Snapshot.current.hasPendingChanges() ||
                    Snapshot.isApplyObserverNotificationPending || scene.hasInvalidations())
            }
        } catch (failure: TimeoutCancellationException) {
            throw AssertionError("Cascade condition timed out: ${nodes().map { it.config }}", failure)
        }
    }
    override fun close() {
        scene.close()
        if (failures.isNotEmpty()) throw AssertionError("Cascade disposal failed", failures.first())
    }
}

@JsFun("""(host, label) => {
    function find(root) {
        for (const node of root.querySelectorAll("*")) {
            if (node.getAttribute("aria-label") === label) return node;
            if (node.shadowRoot) { const found = find(node.shadowRoot); if (found) return found; }
        }
        return null;
    }
    return find(host);
}""")
private external fun cascadeDomElement(host: HTMLElement, label: String): HTMLElement?

@JsFun("""(host) => {
    function labels(root) {
        return Array.from(root.querySelectorAll("*")).flatMap(n =>
            [n.getAttribute("aria-label"), ...(n.shadowRoot ? labels(n.shadowRoot) : [])]).filter(Boolean);
    }
    return labels(host).join(" | ");
}""")
private external fun cascadeDomLabels(host: HTMLElement): String

@JsFun("""(host, x, y) => {
    function canvas(root) {
        const direct = root.querySelector("canvas");
        if (direct) return direct;
        for (const n of root.querySelectorAll("*")) if (n.shadowRoot) {
            const found = canvas(n.shadowRoot); if (found) return found;
        }
        return null;
    }
    const target = canvas(host);
    if (!target) throw new Error("No cascade canvas");
    target.focus();
    for (const type of ["pointerenter", "pointermove", "pointerdown", "pointerup"])
        target.dispatchEvent(new PointerEvent(type, {pointerId: 1, pointerType: "mouse",
            clientX:x, clientY:y, button:0, buttons:type === "pointerdown" ? 1 : 0,
            bubbles:true, cancelable:true, isPrimary:true}));
}""")
private external fun cascadeDomPress(host: HTMLElement, x: Double, y: Double)

@JsFun("""(host, key, keyCode) => {
    function canvas(root) {
        const direct = root.querySelector("canvas");
        if (direct) return direct;
        for (const n of root.querySelectorAll("*")) if (n.shadowRoot) {
            const found = canvas(n.shadowRoot); if (found) return found;
        }
        return null;
    }
    const target = canvas(host); target.focus();
    for (const type of ["keydown", "keyup"])
        target.dispatchEvent(new KeyboardEvent(type, {key, code:key, keyCode, which:keyCode,
            bubbles:true, cancelable:true}));
}""")
private external fun cascadeDomKey(host: HTMLElement, key: String, keyCode: Int)
