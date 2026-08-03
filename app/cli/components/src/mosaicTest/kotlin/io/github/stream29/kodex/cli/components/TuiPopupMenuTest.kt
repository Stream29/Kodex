package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.focus.FocusState
import com.jakewharton.mosaic.focus.onFocusChanged
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.terminal.Terminal
import com.jakewharton.mosaic.testing.SnapshotStrategy
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.TimeoutCancellationException

private val popupMenuAnsiSnapshots = SnapshotStrategy { mosaic ->
    mosaic.draw().render(AnsiLevel.TRUECOLOR, supportsKittyUnderlines = false)
}

val tuiPopupMenuTest by testSuite {
    test("keyboard navigation selects by stable key and dismisses the menu") {
        var expanded by mutableStateOf(true)
        var selected by mutableStateOf("")
        val state = TuiPopupMenuState(initialFocusedKey = "low")

        runMosaicTest {
            setContentAndSnapshot {
                Box {
                    PopupMenuHarness(
                        expanded = expanded,
                        state = state,
                        onDismissRequest = { expanded = false },
                    ) {
                        listOf("low", "medium", "high").forEach { value ->
                            TuiPopupMenuItem(key = value, onClick = { selected = value }) {
                                Text(value)
                            }
                        }
                    }
                }
            }
            awaitSnapshotContaining("[medium]")

            sendKeyEvent(KeyboardEvent(codepoint = 57353))
            awaitSnapshotUntil { state.focusedKey == "medium" }
            assertEquals("medium", state.focusedKey)

            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitSnapshot()
        }

        assertEquals("medium", selected)
        assertFalse(expanded)
    }

    test("tab navigation reaches items outside the initial viewport") {
        val state = TuiPopupMenuState(initialFocusedKey = 0)

        runMosaicTest {
            setContentAndSnapshot {
                Box {
                    PopupMenuHarness(state = state) {
                        repeat(8) { index ->
                            TuiPopupMenuItem(key = index, onClick = {}) { Text(index.toString()) }
                        }
                    }
                }
            }
            awaitSnapshotContaining("[1]")

            repeat(7) { index ->
                sendKeyEvent(KeyboardEvent(codepoint = 9))
                awaitSnapshotUntil { state.focusedKey == index + 1 }
                assertEquals(index + 1, state.focusedKey)
            }
            awaitSnapshotUntil { state.viewport.lastVisibleKey == 7 }

            assertEquals(7, state.focusedKey)
            assertEquals(7, state.viewport.lastVisibleKey)
            assertFalse(0 in state.viewport.visibleIndices)
        }
    }

    test("pointer activation selects the matching item") {
        var expanded by mutableStateOf(true)
        var selected by mutableStateOf("")
        val state = TuiPopupMenuState()

        runMosaicTest {
            setContentAndSnapshot {
                Box {
                    PopupMenuHarness(
                        expanded = expanded,
                        state = state,
                        onDismissRequest = { expanded = false },
                    ) {
                        listOf("low", "medium", "high").forEach { value ->
                            TuiPopupMenuItem(key = value, onClick = { selected = value }) {
                                Text(value)
                            }
                        }
                    }
                }
            }
            awaitSnapshotContaining("[medium]")

            sendMouseEvent(MouseEvent(1, 5, MouseEvent.Type.Press, MouseEvent.Button.Left))
            awaitSnapshot()
            sendMouseEvent(MouseEvent(1, 5, MouseEvent.Type.Release))
            awaitSnapshot()
        }

        assertEquals("medium", selected)
        assertFalse(expanded)
    }

    test("disabled items cannot receive focus or pointer activation") {
        var disabledInvocations = 0
        val state = TuiPopupMenuState(initialFocusedKey = "disabled")

        runMosaicTest {
            setContentAndSnapshot {
                Box {
                    PopupMenuHarness(state = state) {
                        TuiPopupMenuItem(
                            key = "disabled",
                            enabled = false,
                            onClick = { disabledInvocations++ },
                        ) {
                            Text("disabled")
                        }
                        TuiPopupMenuItem(key = "enabled", onClick = {}) { Text("enabled") }
                    }
                }
            }

            awaitSnapshotContaining("[disabled]")
            assertEquals("enabled", state.focusedKey)
            sendMouseEvent(MouseEvent(1, 5, MouseEvent.Type.Press, MouseEvent.Button.Left))
            sendMouseEvent(MouseEvent(1, 5, MouseEvent.Type.Release))
            awaitSnapshot()
        }

        assertEquals(0, disabledInvocations)
    }

    test("escape dismisses a menu without an enabled item") {
        var expanded by mutableStateOf(true)
        val state = TuiPopupMenuState()

        runMosaicTest {
            setContentAndSnapshot {
                Box {
                    PopupMenuHarness(
                        expanded = expanded,
                        state = state,
                        onDismissRequest = { expanded = false },
                    ) {
                        TuiPopupMenuItem(
                            key = "disabled",
                            enabled = false,
                            onClick = {},
                        ) {
                            Text("disabled")
                        }
                    }
                }
            }
            awaitSnapshotContaining("[disabled]")
            assertEquals(null, state.focusedKey)

            sendKeyEvent(KeyboardEvent(codepoint = 27))
            awaitSnapshot()
        }

        assertFalse(expanded)
    }

    test("selected item supplies the initial focus key") {
        val state = TuiPopupMenuState()

        runMosaicTest(snapshotStrategy = popupMenuAnsiSnapshots) {
            setContentAndSnapshot {
                Box {
                    PopupMenuHarness(state = state) {
                        TuiPopupMenuItem(key = "first", onClick = {}) { Text("first") }
                        TuiPopupMenuItem(key = "selected", selected = true, onClick = {}) {
                            Text("selected")
                        }
                    }
                }
            }
            val snapshot = awaitSnapshotUntil { state.focusedKey == "selected" }
            assertTrue("\u001B[1m[selected]\u001B[22m" in snapshot, snapshot)
        }

        assertEquals("selected", state.focusedKey)
    }

    test("disabling the focused item selects its next enabled neighbor") {
        var middleEnabled by mutableStateOf(true)
        val state = TuiPopupMenuState(initialFocusedKey = "middle")

        runMosaicTest {
            setContentAndSnapshot {
                Box {
                    PopupMenuHarness(state = state) {
                        TuiPopupMenuItem(key = "first", onClick = {}) { Text("first") }
                        TuiPopupMenuItem(
                            key = "middle",
                            enabled = middleEnabled,
                            onClick = {},
                        ) {
                            Text("middle")
                        }
                        TuiPopupMenuItem(key = "last", onClick = {}) { Text("last") }
                    }
                }
            }
            awaitSnapshotUntil { state.focusedKey == "middle" }

            middleEnabled = false
            awaitSnapshotUntil { state.focusedKey == "last" }
        }

        assertEquals("last", state.focusedKey)
    }

    test("items align by terminal cell width") {
        val state = TuiPopupMenuState()

        runMosaicTest {
            setContentAndSnapshot {
                Box {
                    PopupMenuHarness(
                        state = state,
                        width = 8,
                        height = 3,
                    ) {
                        TuiPopupMenuItem(key = "wide", onClick = {}) { Text("\u4f60") }
                        TuiPopupMenuItem(key = "ascii", onClick = {}) { Text("abc") }
                    }
                }
            }
            val snapshot = awaitSnapshotContaining("[abc]")

            assertEquals("[\u4f60 ]xxx", snapshot.lines()[0])
            assertEquals("[abc]xxx", snapshot.lines()[1])
        }
    }

    test("item slots and dividers share the measured menu width") {
        val state = TuiPopupMenuState()

        runMosaicTest {
            setContentAndSnapshot {
                Box {
                    PopupMenuHarness(state = state, width = 20, height = 4) {
                        TuiPopupMenuItem(
                            key = "open",
                            leadingContent = { Text("*") },
                            trailingContent = { Text("Ctrl+1") },
                            onClick = {},
                        ) {
                            Text("open")
                        }
                        TuiPopupMenuDivider(key = "divider")
                        TuiPopupMenuItem(key = "close", onClick = {}) { Text("close") }
                    }
                }
            }
            val snapshot = awaitSnapshotContaining("[* open Ctrl+1]")

            assertTrue(snapshot.lines()[0].startsWith("[* open Ctrl+1]"), snapshot)
            assertTrue(snapshot.lines()[1].startsWith("-".repeat(15)), snapshot)
            assertTrue(snapshot.lines()[2].startsWith("[close        ]"), snapshot)
        }
    }

    test("right opens a submenu and left restores its parent") {
        val state = TuiPopupMenuState(initialFocusedKey = "model")

        runMosaicTest {
            setContentAndSnapshot {
                Box {
                    PopupMenuHarness(state = state) {
                        TuiPopupSubmenuItem(
                            key = "model",
                            submenuContent = {
                                TuiPopupMenuItem(key = "low", onClick = {}) { Text("low") }
                                TuiPopupMenuItem(key = "high", onClick = {}) { Text("high") }
                            },
                        ) {
                            Text("model")
                        }
                    }
                }
            }
            awaitSnapshotContaining("[model >]")

            sendKeyEvent(KeyboardEvent(codepoint = 57351))
            awaitSnapshotContaining("[low ]")
            assertEquals("model", state.openSubmenuKey)

            sendKeyEvent(KeyboardEvent(codepoint = 57350))
            awaitSnapshotNotContaining("[low ]")
            assertEquals(null, state.openSubmenuKey)
            assertEquals("model", state.focusedKey)
        }
    }

    test("enter opens a submenu and escape restores its parent") {
        val state = TuiPopupMenuState(initialFocusedKey = "model")

        runMosaicTest {
            setContentAndSnapshot {
                Box {
                    PopupMenuHarness(state = state) {
                        TuiPopupSubmenuItem(
                            key = "model",
                            initialSubmenuFocusedKey = "high",
                            submenuContent = {
                                TuiPopupMenuItem(key = "low", onClick = {}) { Text("low") }
                                TuiPopupMenuItem(key = "high", onClick = {}) { Text("high") }
                            },
                        ) {
                            Text("model")
                        }
                    }
                }
            }
            awaitSnapshotContaining("[model >]")

            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitSnapshotContaining("[high]")
            assertEquals("model", state.openSubmenuKey)

            sendKeyEvent(KeyboardEvent(codepoint = 27))
            awaitSnapshotNotContaining("[high]")
            assertEquals(null, state.openSubmenuKey)
            assertEquals("model", state.focusedKey)
        }
    }

    test("selecting a submenu item dismisses the whole popup group") {
        var expanded by mutableStateOf(true)
        var selected = ""
        val state = TuiPopupMenuState(initialFocusedKey = "model")

        runMosaicTest {
            setContentAndSnapshot {
                Box {
                    PopupMenuHarness(
                        expanded = expanded,
                        state = state,
                        onDismissRequest = { expanded = false },
                    ) {
                        TuiPopupSubmenuItem(
                            key = "model",
                            submenuContent = {
                                TuiPopupMenuItem(key = "low", onClick = { selected = "low" }) {
                                    Text("low")
                                }
                            },
                        ) {
                            Text("model")
                        }
                    }
                }
            }
            awaitSnapshotContaining("[model >]")

            sendKeyEvent(KeyboardEvent(codepoint = 57351))
            awaitSnapshotContaining("[low]")
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitSnapshot()
        }

        assertEquals("low", selected)
        assertFalse(expanded)
    }

    test("wheel scrolling moves a bounded variable-height viewport") {
        val state = TuiPopupMenuState(initialFocusedKey = 0)

        runMosaicTest {
            setContentAndSnapshot {
                Box {
                    PopupMenuHarness(state = state) {
                        repeat(8) { index ->
                            TuiPopupMenuItem(key = index, onClick = {}) { Text(index.toString()) }
                        }
                    }
                }
            }
            awaitSnapshotContaining("[1]")

            sendMouseEvent(MouseEvent(1, 1, MouseEvent.Type.Press, MouseEvent.Button.WheelDown))
            awaitSnapshotUntil { state.viewport.firstVisibleKey == 1 }

            assertEquals(1, state.viewport.firstVisibleKey)
            assertTrue(state.viewport.canScrollBackward)
        }
    }

    test("terminal resize recomputes the bounded viewport") {
        val state = TuiPopupMenuState(initialFocusedKey = 0)

        runMosaicTest {
            this.state.size.value = Terminal.Size(columns = 24, rows = 8)
            setContentAndSnapshot {
                TerminalSizedPopupMenuHarness(state)
            }
            awaitSnapshotUntil {
                state.viewport.firstVisibleKey == 0 &&
                    state.viewport.visibleIndices.count() > 2 &&
                    state.viewport.canScrollForward
            }
            val initialVisibleCount = state.viewport.visibleIndices.count()

            this.state.size.value = Terminal.Size(columns = 24, rows = 4)
            setContentAndSnapshot {
                TerminalSizedPopupMenuHarness(state)
            }
            awaitSnapshotUntil {
                state.viewport.visibleIndices.count() < initialVisibleCount &&
                    state.viewport.canScrollForward
            }

            assertTrue(state.viewport.visibleIndices.count() < initialVisibleCount)
            assertTrue(state.viewport.canScrollForward)
        }
    }

    test("removing the focused key selects the adjacent enabled item") {
        var items by mutableStateOf(listOf("a", "b", "c"))
        val state = TuiPopupMenuState(initialFocusedKey = "b")

        runMosaicTest {
            setContentAndSnapshot {
                Box {
                    PopupMenuHarness(state = state) {
                        items.forEach { value ->
                            TuiPopupMenuItem(key = value, onClick = {}) { Text(value) }
                        }
                    }
                }
            }
            awaitSnapshot()
            assertEquals("b", state.focusedKey)

            items = listOf("a", "c")
            awaitSnapshot()
            assertEquals("c", state.focusedKey)
        }
    }

    test("configured background clears every cell in the menu rectangle") {
        val state = TuiPopupMenuState()

        runMosaicTest(snapshotStrategy = popupMenuAnsiSnapshots) {
            setContentAndSnapshot {
                Box {
                    PopupMenuHarness(
                        state = state,
                        width = 8,
                        height = 3,
                        backgroundColor = Color(12, 34, 56),
                    ) {
                        TuiPopupMenuItem(key = "short", onClick = {}) { Text("a") }
                        TuiPopupMenuItem(key = "long", onClick = {}) { Text("long") }
                    }
                }
            }
            val snapshot = awaitSnapshotContaining("[long]")

            assertTrue("\u001B[48;2;12;34;56m" in snapshot, snapshot)
            assertTrue("[a   ]xx" in snapshot.withoutAnsi(), snapshot)
        }
    }

    test("unspecified background leaves unpainted cells transparent") {
        val state = TuiPopupMenuState()

        runMosaicTest {
            setContentAndSnapshot {
                Box {
                    PopupMenuHarness(
                        state = state,
                        width = 8,
                        height = 3,
                    ) {
                        TuiPopupMenuItem(key = "short", onClick = {}) { Text("a") }
                        TuiPopupMenuItem(key = "long", onClick = {}) { Text("long") }
                    }
                }
            }
            val snapshot = awaitSnapshotContaining("[long]")

            assertEquals("[a   ]xx", snapshot.lines()[0])
        }
    }

    test("dismissal restores focus to the trigger") {
        var expanded by mutableStateOf(false)
        var triggerFocused by mutableStateOf(false)

        runMosaicTest {
            setContentAndSnapshot {
                Box {
                    FocusRestorationHarness(
                        expanded = expanded,
                        isTriggerFocused = triggerFocused,
                        triggerFocused = { triggerFocused = it },
                        onExpandedChange = { expanded = it },
                    )
                }
            }
            awaitSnapshotContaining("[<trigger>]")

            expanded = true
            awaitSnapshotContaining("[item]")
            assertFalse(triggerFocused)

            sendKeyEvent(KeyboardEvent(codepoint = 27))
            awaitSnapshotContaining("[<trigger>]")
        }

        assertFalse(expanded)
        assertTrue(triggerFocused)
    }

    test("menu passes non-Escape keys to its focus path") {
        var unhandledEvents by mutableStateOf(0)
        val state = TuiPopupMenuState()

        runMosaicTest {
            setContentAndSnapshot {
                Box(
                    modifier = Modifier.onKeyEvent {
                        unhandledEvents++
                        true
                    },
                ) {
                    PopupMenuHarness(state = state) {
                        TuiPopupMenuItem(key = "item", onClick = {}) { Text("item") }
                    }
                }
            }
            awaitSnapshotContaining("[item]")

            sendKeyEvent(
                KeyboardEvent(
                    codepoint = 'n'.code,
                    modifiers = KeyboardEvent.ModifierCtrl,
                ),
            )
            awaitSnapshotUntil { unhandledEvents == 1 }
        }

        assertEquals(1, unhandledEvents)
    }

    test("outside click dismisses an open submenu and its parent") {
        var expanded by mutableStateOf(true)
        val state = TuiPopupMenuState(initialFocusedKey = "model")

        runMosaicTest {
            setContentAndSnapshot {
                Box {
                    PopupMenuHarness(
                        expanded = expanded,
                        state = state,
                        onDismissRequest = { expanded = false },
                    ) {
                        TuiPopupSubmenuItem(
                            key = "model",
                            submenuContent = {
                                TuiPopupMenuItem(key = "low", onClick = {}) { Text("low") }
                            },
                        ) {
                            Text("model")
                        }
                    }
                }
            }
            awaitSnapshotContaining("[model >]")
            sendKeyEvent(KeyboardEvent(codepoint = 57351))
            awaitSnapshotContaining("[low]")

            sendMouseEvent(MouseEvent(20, 0, MouseEvent.Type.Press, MouseEvent.Button.Left))
            awaitSnapshot()
        }

        assertFalse(expanded)
    }

    test("an open submenu leaves its parent items pointer-accessible") {
        var expanded by mutableStateOf(true)
        var selected = ""
        val state = TuiPopupMenuState(initialFocusedKey = "model")

        runMosaicTest {
            setContentAndSnapshot {
                Box {
                    PopupMenuHarness(
                        expanded = expanded,
                        state = state,
                        onDismissRequest = { expanded = false },
                    ) {
                        TuiPopupSubmenuItem(
                            key = "model",
                            submenuContent = {
                                TuiPopupMenuItem(key = "low", onClick = {}) { Text("low") }
                            },
                        ) {
                            Text("model")
                        }
                        TuiPopupMenuItem(key = "quit", onClick = { selected = "quit" }) {
                            Text("quit")
                        }
                    }
                }
            }
            awaitSnapshotContaining("[model >]")
            sendKeyEvent(KeyboardEvent(codepoint = 57351))
            awaitSnapshotContaining("[low]")

            sendMouseEvent(MouseEvent(1, 6, MouseEvent.Type.Press, MouseEvent.Button.Left))
            awaitSnapshot()
            sendMouseEvent(MouseEvent(1, 6, MouseEvent.Type.Release))
            awaitSnapshot()
        }

        assertEquals("quit", selected)
        assertFalse(expanded)
    }
}

@Composable
private fun TerminalSizedPopupMenuHarness(state: TuiPopupMenuState) {
    val terminalSize = LocalTerminalState.current.size
    Box {
        PopupMenuHarness(
            state = state,
            width = terminalSize.columns,
            height = terminalSize.rows,
        ) {
            repeat(8) { index ->
                TuiPopupMenuItem(key = index, onClick = {}) { Text(index.toString()) }
            }
        }
    }
}

@Composable
private fun PopupMenuHarness(
    state: TuiPopupMenuState,
    expanded: Boolean = true,
    width: Int = 24,
    height: Int = 8,
    backgroundColor: Color = Color.Unspecified,
    onDismissRequest: () -> Unit = {},
    content: TuiPopupMenuScope.() -> Unit,
) {
    val anchor = rememberTuiPopupAnchor()
    TuiPopupHost(
        modifier = Modifier
            .width(width)
            .height(height),
    ) {
        Column(modifier = Modifier.matchParentSize()) {
            repeat((height - 1).coerceAtLeast(0)) {
                Text("x".repeat(width))
            }
            Text("[trigger]", modifier = Modifier.tuiPopupAnchor(anchor))
        }
        TuiPopupMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            anchor = anchor,
            state = state,
            backgroundColor = backgroundColor,
            content = content,
        )
    }
}

@Composable
private fun FocusRestorationHarness(
    expanded: Boolean,
    isTriggerFocused: Boolean,
    triggerFocused: (Boolean) -> Unit,
    onExpandedChange: (Boolean) -> Unit,
) {
    val anchor = rememberTuiPopupAnchor()
    TuiPopupHost(
        modifier = Modifier
            .width(24)
            .height(8),
    ) {
        Column(modifier = Modifier.matchParentSize()) {
            repeat(7) { Text("x".repeat(24)) }
            TuiButton(
                label = if (isTriggerFocused) "<trigger>" else "trigger",
                modifier = Modifier
                    .tuiPopupAnchor(anchor)
                    .onFocusChanged { triggerFocused(it == FocusState.Active) },
                onClick = { onExpandedChange(true) },
            )
        }
        TuiPopupMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            anchor = anchor,
        ) {
            TuiPopupMenuItem(key = "item", onClick = {}) { Text("item") }
        }
    }
}

private suspend fun TestMosaic<String>.awaitSnapshotContaining(expected: String): String {
    var latest = ""
    repeat(5) {
        latest = try {
            awaitSnapshot()
        } catch (_: TimeoutCancellationException) {
            draw().render(AnsiLevel.NONE, supportsKittyUnderlines = false)
        }
        if (expected in latest) return latest
    }
    assertTrue(expected in latest, latest)
    return latest
}

private suspend fun TestMosaic<String>.awaitSnapshotNotContaining(unexpected: String): String {
    var latest = ""
    repeat(5) {
        latest = try {
            awaitSnapshot()
        } catch (_: TimeoutCancellationException) {
            draw().render(AnsiLevel.NONE, supportsKittyUnderlines = false)
        }
        if (unexpected !in latest) return latest
    }
    assertFalse(unexpected in latest, latest)
    return latest
}

private suspend fun TestMosaic<String>.awaitSnapshotUntil(predicate: () -> Boolean): String {
    var latest = ""
    repeat(5) {
        latest = try {
            awaitSnapshot()
        } catch (_: TimeoutCancellationException) {
            draw().render(AnsiLevel.NONE, supportsKittyUnderlines = false)
        }
        if (predicate()) return latest
    }
    assertTrue(predicate(), latest)
    return latest
}

private fun String.withoutAnsi(): String = replace(Regex("\u001B\\[[0-9;]*m"), "")
