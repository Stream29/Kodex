package io.github.stream29.kodex.cli.sessioncatalog

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.unit.IntOffset
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.cli.components.TuiPopupHost
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

val mosaicSessionGroupsRendererTest by testSuite {
    test("nullable directory actions stay anchored and pass null to the caller") {
        var archived by mutableStateOf(false)
        var panelMounted by mutableStateOf(true)
        val created = mutableListOf<String?>()
        val toggled = mutableListOf<String?>()
        val panelActions = mutableListOf<String>()
        runMosaicTest {
            val first = setContentAndSnapshot {
                TuiPopupHost(Modifier.width(60).height(8)) {
                    val renderer = rememberMosaicSessionGroupsRenderer(columns = PanelWidth)
                    Row {
                        if (panelMounted) {
                            renderer.Panel(
                                rows = listOf(groupHeader(showArchived = archived)),
                                onRefresh = { panelActions += "refresh" },
                                onCollapse = { panelActions += "collapse" },
                                onToggleGroup = {},
                                onNewInGroup = { created += it },
                                onToggleArchived = { toggled += it; archived = !archived },
                                onToggleLimit = {},
                                onOpen = {},
                                onContextMenu = { _, _ -> },
                            )
                        } else {
                            Text("Panel closed")
                        }
                    }
                    renderer.Overlays()
                }
            }
            assertTrue(first.lineSequence().first().contains("[↻][—]"), first)
            assertTrue(first.lineSequence().drop(1).first().contains("[+][…]"), first)

            click(19, 0)
            click(22, 0)
            click(19, 1)
            click(22, 1)
            val menu = awaitText("[ ] Show archived")
            // The narrow panel ends at 24, so this popup is drawn in the wider root host.
            assertTrue(menu.lineSequence().any { it.indexOf("Show archived") >= PanelWidth }, menu)
            assertEquals(listOf("refresh", "collapse"), panelActions)
            assertEquals(listOf<String?>(null), created)

            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitText("[+][…]", absent = "Show archived")
            assertEquals(listOf<String?>(null), toggled)
            click(22, 1)
            awaitText("[x] Show archived")
            panelMounted = false
            awaitText("Panel closed", absent = "Show archived")
        }
    }

    test("session rows indent twice; pointer and keyboard menus retain the exact target") {
        var context by mutableStateOf<Pair<SessionGroupRow.Entry, SessionCatalogMenuOrigin>?>(null)
        val opened = mutableListOf<Int>()
        val renamed = mutableListOf<Int>()
        val firstRow = sessionRow(4, "First", selected = true)
        val secondRow = sessionRow(5, "Second", archived = true)
        runMosaicTest {
            val screen = setContentAndSnapshot {
                TuiPopupHost(Modifier.width(60).height(10)) {
                    val renderer = rememberMosaicSessionGroupsRenderer(columns = PanelWidth)
                    Row {
                        renderer.Panel(
                            rows = listOf(groupHeader(), firstRow, secondRow, SessionGroupRow.Limit(null, 7)),
                            onRefresh = {},
                            onCollapse = {},
                            onToggleGroup = {},
                            onNewInGroup = {},
                            onToggleArchived = {},
                            onToggleLimit = {},
                            onOpen = { opened += it.entry.sessionIndex },
                            onContextMenu = { row, origin -> context = row to origin },
                        )
                        context?.let { (row, origin) ->
                            renderer.ContextMenu(
                                row, origin,
                                onDismiss = { context = null },
                                onRename = { renamed += row.entry.sessionIndex; context = null },
                                onToggleArchived = { context = null },
                                onDelete = { context = null },
                            )
                        }
                    }
                    renderer.Overlays()
                }
            }
            val lines = screen.lines()
            assertTrue(lines[2].startsWith("  [First]"), screen)
            assertTrue(lines[3].startsWith("  [Second]"), screen)
            assertTrue(lines[4].startsWith("  Show more (7)"), screen)

            click(9, 3, MouseEvent.Button.Right)
            val menu = awaitText("Unarchive")
            assertTrue("Index: 5" in menu && "Rename" in menu && "Delete" in menu, menu)
            assertEquals(secondRow, context?.first)
            assertEquals(IntOffset(7, 0), (context?.second as MosaicCatalogOrigin).clickPosition)
            assertTrue(opened.isEmpty())

            // Rename is first actionable item; its callback closes this exact opening.
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitText("Show more (7)", absent = "Unarchive")
            assertEquals(listOf(5), renamed)

            click(5, 2)
            sendKeyEvent(KeyboardEvent(codepoint = KeyboardEvent.F10, modifiers = KeyboardEvent.ModifierShift))
            val keyboardMenu = awaitText("Index: 4")
            assertFalse("Unarchive" in keyboardMenu, keyboardMenu)
            assertEquals(listOf(4), opened)
            assertEquals(firstRow, context?.first)
            assertNull((context?.second as MosaicCatalogOrigin).clickPosition)
        }
    }

    test("one large group only reads rows near the visible viewport") {
        var accessCount = 0
        val rows = object : AbstractList<SessionGroupRow>() {
            override val size: Int = 2_001
            override fun get(index: Int): SessionGroupRow {
                accessCount++
                return if (index == 0) groupHeader() else sessionRow(index, "Session entry $index")
            }
        }
        runMosaicTest {
            val screen = setContentAndSnapshot {
                TuiPopupHost(Modifier.width(40).height(4)) {
                    val renderer = rememberMosaicSessionGroupsRenderer(columns = PanelWidth)
                    renderer.Panel(
                        rows = rows,
                        onRefresh = {},
                        onCollapse = {},
                        onToggleGroup = {},
                        onNewInGroup = {},
                        onToggleArchived = {},
                        onToggleLimit = {},
                        onOpen = {},
                        onContextMenu = { _, _ -> },
                    )
                    renderer.Overlays()
                }
            }
            assertTrue("Session entry 1]" in screen, screen)
            assertTrue(accessCount in 1..500, "Viewport read $accessCount rows from ${rows.size}")
        }
    }
}

private const val PanelWidth = 24

private fun groupHeader(showArchived: Boolean = false) = SessionGroupRow.Header(
    cwd = null,
    title = "No directory",
    subtitle = null,
    expanded = true,
    showArchived = showArchived,
    hasRunning = false,
)

private fun sessionRow(index: Int, name: String, selected: Boolean = false, archived: Boolean = false) =
    SessionGroupRow.Entry(null, SessionCatalogEntry(index, name = name, archived = archived), selected)

private fun TestMosaic<String>.click(x: Int, y: Int, button: MouseEvent.Button = MouseEvent.Button.Left) {
    sendMouseEvent(MouseEvent(x, y, MouseEvent.Type.Press, button))
    sendMouseEvent(MouseEvent(x, y, MouseEvent.Type.Release))
}

private suspend fun TestMosaic<String>.awaitText(present: String, absent: String? = null): String {
    var latest = ""
    repeat(5) {
        latest = try {
            awaitSnapshot()
        } catch (_: TimeoutCancellationException) {
            draw().render(AnsiLevel.NONE, supportsKittyUnderlines = false)
        }
        if (present in latest && (absent == null || absent !in latest)) return latest
    }
    error("Expected '$present' without '$absent' in:\n$latest")
}
