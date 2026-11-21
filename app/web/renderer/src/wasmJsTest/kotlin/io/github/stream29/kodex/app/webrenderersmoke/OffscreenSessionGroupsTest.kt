package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.app.sessiondelete.contract.SessionDeleteViewModel
import io.github.stream29.kodex.app.sessiondelete.createSessionDeleteViewModel
import io.github.stream29.kodex.app.ui.compose.ComposeToolWindowRail
import io.github.stream29.kodex.app.ui.compose.rememberComposeSessionGroupsRenderer
import io.github.stream29.kodex.cli.components.formatPopupTimestamp
import io.github.stream29.kodex.cli.sessioncatalog.SessionCatalogMenuOrigin
import io.github.stream29.kodex.cli.sessioncatalog.SessionGroupRow
import io.github.stream29.kodex.cli.settings.ToolWindowId
import io.github.stream29.kodex.cli.settings.ToolWindowSide
import kotlinx.datetime.TimeZone
import kotlin.test.*
import kotlin.time.Instant

val offscreenSessionGroupsTest by testSuite {
    for (dark in listOf(false, true)) {
        test("tool windows have square named controls and independently selectable rails, dark=$dark") { offscreenTest {
            var left by mutableStateOf<ToolWindowId?>(null)
            var right by mutableStateOf<ToolWindowId?>(null)
            val neutralFocus = FocusRequester()
            val neutralSurface = "Neutral test surface"
            val controls = mapOf(
                "Sessions" to ToolWindowId.Sessions,
                "History Index" to ToolWindowId.HistoryIndex,
                "Terminal" to ToolWindowId.TerminalSessions,
            )
            val scene = OffscreenScene.create(width = 240, height = 180) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Row(Modifier.fillMaxSize()) {
                        ComposeToolWindowRail(ToolWindowSide.Left, left, { left = it.takeUnless { it == left } })
                        Box(Modifier.weight(1f).fillMaxHeight().focusRequester(neutralFocus).focusable()
                            .semantics { contentDescription = neutralSurface })
                        ComposeToolWindowRail(ToolWindowSide.Right, right, { right = it.takeUnless { it == right } })
                    }
                }
            }
            try {
                suspend fun expectSelection(action: String, expectedLeft: ToolWindowId?, expectedRight: ToolWindowId?) {
                    assertEquals(expectedLeft, left, "$action: left rail selection")
                    assertEquals(expectedRight, right, "$action: right rail selection")
                    // Measure the rails after pointer and keyboard focus have both left their tooltips.
                    scene.scene.sendPointerEvent(PointerEventType.Move, Offset(120f, 90f))
                    assertTrue(neutralFocus.requestFocus(), "$action: neutral focus accepted")
                    scene.settle("$action: neutral focus and tooltip-free rail semantics") {
                        scene.hasDescription(neutralSurface) && scene.isFocused(neutralSurface) &&
                            controls.keys.all { scene.hasDescription(it) && !scene.hasText(it) }
                    }
                    for ((label, id) in controls) {
                        assertEquals(id == expectedLeft || id == expectedRight,
                            scene.control(label).config.getOrNull(SemanticsProperties.Selected), "$action: $label selected")
                    }
                }

                scene.settle { controls.keys.all(scene::hasDescription) }
                for (label in controls.keys) {
                    val node = scene.control(label)
                    assertEquals(Role.Button, node.config.getOrNull(SemanticsProperties.Role))
                    assertEquals(false, node.config.getOrNull(SemanticsProperties.Selected))
                    assertEquals(36f, node.boundsInWindow.width)
                    assertEquals(36f, node.boundsInWindow.height)
                    assertFalse(scene.hasText(label))
                }
                assertTrue(scene.control("Sessions").boundsInWindow.right <= 40)
                assertTrue(scene.control("History Index").boundsInWindow.left >= 200)
                scene.clickDescription("Sessions")
                expectSelection("Click Sessions", ToolWindowId.Sessions, null)
                assertTrue(scene.control("History Index").config[SemanticsActions.RequestFocus].action!!.invoke())
                assertTrue(scene.isFocused("History Index"))
                scene.key(Key.Enter)
                expectSelection("Enter on History Index", ToolWindowId.Sessions, ToolWindowId.HistoryIndex)
                scene.clickDescription("Terminal")
                expectSelection("Click Terminal", ToolWindowId.Sessions, ToolWindowId.TerminalSessions)
                scene.clickDescription("Terminal")
                expectSelection("Click Terminal again", ToolWindowId.Sessions, null)
                scene.frame()
            } finally { scene.close() }
        } }
    }

    test("narrow panel keeps group actions visible, archives scoped to cwd, and row open does not collapse") { offscreenTest {
        val path = "/one/very/long/directory/project"
        val other = "/two/project"
        var expanded by mutableStateOf(true)
        var showAll by mutableStateOf(false)
        var archived by mutableStateOf(setOf<String?>())
        val events = mutableListOf<Pair<String, String?>>()
        val entry = SessionGroupRow.Entry(path, SessionCatalogEntry(7, "Row seven"), selected = true)
        val scene = OffscreenScene.create(width = 216, height = 310) {
            rememberComposeSessionGroupsRenderer().Panel(
                buildList {
                    add(SessionGroupRow.Header(path, "project", "directory", expanded, path in archived, false))
                    if (expanded) {
                        add(entry)
                        add(SessionGroupRow.Limit(path, if (showAll) null else 13))
                    }
                    add(SessionGroupRow.Header(other, "project", "two", false, other in archived, false))
                    add(SessionGroupRow.Header(null, "未设置目录", null, false, null in archived, false))
                },
                onRefresh = { events += "refresh" to null },
                onCollapse = { events += "collapse" to null },
                onToggleGroup = { if (it == path) expanded = !expanded },
                onNewInGroup = { events += "new" to it },
                onToggleArchived = { archived = if (it in archived) archived - it else archived + it },
                onToggleLimit = { if (it == path) showAll = !showAll },
                onOpen = { events += "open" to it.cwd },
                onContextMenu = { _, _ -> error("Primary click must not open a context menu") },
            )
        }
        try {
            scene.settle { scene.hasDescription("Options for 未设置目录") }
            assertTrue(scene.hasText("directory") && scene.hasText("two"))
            assertFalse(scene.hasText("Show archived"))
            assertTrue(scene.isSelected("Row seven"))
            scene.clickDescription("Refresh sessions")
            scene.clickDescription("New session in $path")
            scene.clickDescription("New session in 未设置目录")
            scene.clickDescription("Row seven")
            assertEquals(listOf("refresh" to null, "new" to path, "new" to null, "open" to path), events)
            assertTrue(scene.hasText("Sessions"))
            scene.click("Show more (13)")
            scene.settle { scene.hasText("Show less") }
            scene.click("Show less")
            scene.settle { scene.hasText("Show more (13)") }

            val options = "Options for $path"
            val anchor = scene.control(options).boundsInWindow
            scene.clickDescription(options)
            scene.settle { scene.overlayTextFocused("Show archived") }
            val menuItemBounds = scene.text("Show archived").boundsInWindow
            assertTrue(menuItemBounds.top >= anchor.bottom && menuItemBounds.right <= 216f)
            assertEquals(ToggleableState.Off, scene.archiveToggleState())
            scene.key(Key.Enter)
            scene.settle { !scene.hasText("Show archived") && scene.isFocused(options) }
            assertEquals(setOf<String?>(path), archived)

            scene.clickDescription("Options for $other")
            scene.settle { scene.overlayTextFocused("Show archived") }
            assertEquals(ToggleableState.Off, scene.archiveToggleState())
            scene.escape()
            scene.settle { !scene.hasText("Show archived") }
            scene.clickDescription(options)
            scene.settle { scene.hasText("Show archived") && scene.archiveToggleState() == ToggleableState.On }
            scene.escape()
            scene.settle { !scene.hasText("Show archived") }

            scene.clickDescription(path)
            scene.settle { !scene.hasText("Row seven") }
            scene.clickDescription("Collapse Sessions")
            assertEquals("collapse" to null, events.last())
        } finally { scene.close() }
    } }

    test("collapsed and expanded groups keep accessible running indicators") { offscreenTest {
        var expanded by mutableStateOf(false)
        val scene = OffscreenScene.create(width = 210, height = 215) {
            rememberComposeSessionGroupsRenderer().Panel(
                buildList {
                    add(SessionGroupRow.Header("/running", "running", null, expanded, false, true))
                    if (expanded) add(SessionGroupRow.Entry("/running", SessionCatalogEntry(3, "Working", running = true), false))
                },
                {}, {}, { expanded = !expanded }, {}, {}, {}, {}, { _, _ -> },
            )
        }
        try {
            fun indicators() = scene.nodes().count {
                it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) == ProgressBarRangeInfo.Indeterminate
            }
            scene.settle(waitForIdle = false) { scene.hasDescription("/running") && indicators() == 1 }
            assertEquals("Collapsed; Running", scene.control("/running").config.getOrNull(SemanticsProperties.StateDescription))
            scene.clickDescription("/running")
            scene.settle(waitForIdle = false) { scene.hasText("Working") && indicators() == 2 }
            assertEquals("Expanded; Running", scene.control("/running").config.getOrNull(SemanticsProperties.StateDescription))
            assertEquals("Running", scene.control("Working").config.getOrNull(SemanticsProperties.StateDescription))
        } finally { scene.close() }
    } }

    test("one expanded directory virtualizes 1501 rows and exact context targets survive neither change nor reuse") {
        offscreenTest {
            val header = SessionGroupRow.Header("/bulk", "bulk", null, true, true, false)
            val timestamp = Instant.parse("2026-10-20T01:02:03Z")
            val entries = List(1501) { index ->
                SessionGroupRow.Entry(
                    header.cwd, SessionCatalogEntry(
                        index, "Bulk row $index",
                        createdAt = timestamp.takeIf { index == 1500 },
                        updatedAt = timestamp.takeIf { index == 1500 },
                        archived = index == 1500,
                    ),
                    selected = false,
                )
            }
            var last by mutableStateOf(entries.last())
            var menu by mutableStateOf<Pair<SessionGroupRow.Entry, SessionCatalogMenuOrigin>?>(null)
            var pendingDelete by mutableStateOf<SessionDeleteViewModel?>(null)
            val commands = mutableListOf<Pair<String, Int>>()
            val scene = OffscreenScene.create(width = 320, height = 270) {
                val renderer = rememberComposeSessionGroupsRenderer()
                renderer.Panel(
                    listOf(header) + entries.dropLast(1) + last,
                    {}, {}, {}, {}, {}, {},
                    onOpen = { commands += "open" to it.entry.sessionIndex },
                    onContextMenu = { row, origin -> menu = row to origin },
                )
                menu?.let { request ->
                    fun dismiss() { if (menu === request) menu = null }
                    val (row, origin) = request
                    renderer.ContextMenu(
                        row, origin, ::dismiss,
                        onRename = { dismiss(); commands += "rename" to row.entry.sessionIndex },
                        onToggleArchived = { dismiss(); commands += "unarchive" to row.entry.sessionIndex },
                        onDelete = {
                            dismiss()
                            pendingDelete = createSessionDeleteViewModel(row.entry.sessionIndex, row.entry.name) {
                                commands += "delete" to it
                                true
                            }
                        },
                    )
                }
                pendingDelete?.let { child -> renderer.Delete(child) { pendingDelete = null } }
            }
            try {
                scene.settle { scene.hasText("Bulk row 0") }
                assertFalse(scene.hasText("Bulk row 1500"))
                assertTrue(scene.composedGroupEntries() in 1..12)
                val scroll = scene.nodes().mapNotNull { it.config.getOrNull(SemanticsActions.ScrollToIndex)?.action }.single()
                assertTrue(scroll(1501))
                scene.settle { scene.hasText("Bulk row 1500") }
                assertFalse(scene.hasText("Bulk row 0"))
                assertTrue(scene.composedGroupEntries() in 1..12)

                scene.contextDescription("Bulk row 1500")
                scene.settle { scene.overlayTextFocused("Rename") }
                val formatted = formatPopupTimestamp(timestamp, TimeZone.currentSystemDefault())
                assertTrue(scene.hasText("Created at: $formatted"))
                assertTrue(scene.hasText("Updated at: $formatted"))
                assertTrue(scene.hasText("Unarchive"))
                assertFalse(scene.hasText("Fork"))
                scene.key(Key.Enter)
                scene.settle { !scene.hasText("Rename") && scene.isFocused("Bulk row 1500") }
                assertEquals(listOf("rename" to 1500), commands)

                scene.key(Key.F10, shift = true)
                scene.settle { scene.overlayTextFocused("Rename") }
                scene.click("Unarchive")
                scene.settle { !scene.hasText("Rename") && scene.isFocused("Bulk row 1500") }
                val expected = listOf("rename" to 1500, "unarchive" to 1500)
                assertEquals(expected, commands)
                scene.key(Key.F10, shift = true)
                scene.settle { scene.overlayTextFocused("Rename") }
                val stale = scene.controlText("Unarchive").config[SemanticsActions.OnClick].action!!
                last = last.copy(entry = last.entry.copy(name = "Replacement", rootAgentId = 9000))
                scene.settle { !scene.hasText("Rename") && scene.isFocused("Refresh sessions") }
                stale()
                assertEquals(expected, commands)

                scene.contextDescription("Replacement")
                scene.settle { scene.overlayTextFocused("Rename") }
                scene.click("Delete")
                scene.settle { scene.hasText("Delete Replacement?") && scene.overlayTextFocused("Cancel") }
                assertEquals(1500, pendingDelete?.sessionIndex)
                scene.click("Cancel")
                scene.settle { !scene.hasText("Delete Replacement?") }
                assertEquals(expected, commands)
            } finally { scene.close() }
        }
    }
}

private fun OffscreenScene.control(label: String) = nodes().single {
    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
}

private fun OffscreenScene.controlText(label: String) = nodes().single {
    it.config.getOrNull(SemanticsActions.OnClick) != null &&
        it.children.any { child ->
            child.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true
        }
}

private fun OffscreenScene.isFocused(label: String) =
    control(label).config.getOrNull(SemanticsProperties.Focused) == true

private fun OffscreenScene.isSelected(label: String) =
    control(label).config.getOrNull(SemanticsProperties.Selected) == true

private fun OffscreenScene.archiveToggleState() =
    controlText("Show archived").config.getOrNull(SemanticsProperties.ToggleableState)

private fun OffscreenScene.composedGroupEntries() = nodes().count {
    it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("session-catalog-row-") == true
}
