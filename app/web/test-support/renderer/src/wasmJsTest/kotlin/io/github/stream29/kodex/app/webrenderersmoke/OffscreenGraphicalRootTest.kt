@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.application.contract.*
import io.github.stream29.kodex.app.session.contract.*
import io.github.stream29.kodex.app.ui.compose.ComposeApplicationScreen
import io.github.stream29.kodex.cli.app.SessionSidebarSide
import io.github.stream29.kodex.cli.settings.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

val offscreenGraphicalRootTest by testSuite {
    test("graphical root keeps captured tabs and converts physical sidebar drag into logical columns") { offscreenTest {
        val first = loadingSession(1, "first")
        val second = loadingSession(2, "second")
        val closed = mutableListOf<PersistedSessionViewModel>()
        val app = object : NavigationApplication(listOf(first, second)) {
            override val popup = MutableStateFlow<ApplicationPopupState>(ApplicationPopupState.Closed)
            override suspend fun closeTab(target: PersistedSessionViewModel): Boolean {
                closed += target
                val current = navigation.value
                navigation.value = ApplicationNavigationState(
                    current.tabs.filterNot { it === target }, ApplicationTabSelection.SelectedTab(0),
                )
                return true
            }
        }
        app.navigation.value = app.navigation.value.copy(selection = ApplicationTabSelection.SelectedTab(0))
        val sidebars = RootSidebarPreferences()
        val failures = mutableListOf<Throwable>()
        val newLineKey = MutableStateFlow(NewLineKey.ShiftEnter)
        val scene = OffscreenScene.create {
            ComposeApplicationScreen(app, newLineKey, sidebars, failures::add, { false })
        }
        try {
            scene.settle { scene.hasText("Loading Session…") && sidebars.initialViewport != null }
            assertTrue(assertNotNull(sidebars.initialViewport) in 20..400) // scene width is 800 physical pixels
            scene.click("second")
            scene.settle { app.selected.singleOrNull() === second }
            scene.contextTabText("second")
            scene.settle { scene.hasText("Index: 2") && scene.hasText("Close") }
            scene.click("Close")
            scene.settle { closed.singleOrNull() === second && !scene.hasText("Index: 2") }
            assertSame(first, app.navigation.value.selectedSession)
            scene.clickDescription("Expand left sidebar")
            scene.clickDescription("Expand right sidebar")
            scene.settle { scene.hasDescription("Left sidebar") && scene.hasDescription("Right sidebar") }
            val initial = scene.descriptionBounds("Left sidebar").width
            scene.dragRootSplitter(48f)
            scene.settle { sidebars.resized.isNotEmpty() }
            val columns = sidebars.resized.single().second
            assertEquals(SessionSidebarSide.Left, sidebars.resized.single().first)
            assertTrue(columns in 13..24, "Expected columns near the original 12, not a raw pixel value: $columns")
            assertTrue(scene.descriptionBounds("Left sidebar").width > initial)
            sidebars.state.value = sidebars.state.value.copy(leftWidth = 20)
            scene.settle { scene.descriptionBounds("Left sidebar").width > initial * 1.4f }
            scene.clickDescription("Sidebar content left")
            scene.settle { scene.hasText("None") }
            scene.click("None")
            scene.settle { sidebars.state.value.left == SidebarContent.None }
            assertEquals(listOf(SessionSidebarSide.Left to SidebarContent.None), sidebars.selected)
            scene.clickDescription("Hide left sidebar")
            scene.settle { !scene.hasDescription("Left sidebar") }
            assertTrue(failures.isEmpty())
            scene.frame()
        } finally { scene.close() }
        assertEquals(0, first.closeCount + second.closeCount + app.closeCount)
    } }
    test("narrow loading root keeps finite main viewport and mounted sidebar controls without a DOM substitute") { offscreenTest {
        val session = loadingSession(4, "narrow")
        val app = object : NavigationApplication(listOf(session)) {
            override val popup = MutableStateFlow<ApplicationPopupState>(ApplicationPopupState.Closed)
        }
        app.navigation.value = app.navigation.value.copy(selection = ApplicationTabSelection.SelectedTab(0))
        val sidebars = RootSidebarPreferences()
        val failures = mutableListOf<Throwable>()
        val newLineKey = MutableStateFlow(NewLineKey.Enter)
        val scene = OffscreenScene.create {
            Box(Modifier.width(320.dp).height(500.dp)) {
                ComposeApplicationScreen(app, newLineKey, sidebars, failures::add, { false })
            }
        }
        try {
            scene.settle { scene.hasText("Loading Session…") }
            scene.clickDescription("Expand left sidebar")
            scene.clickDescription("Expand right sidebar")
            scene.settle { scene.hasDescription("Left sidebar") && scene.hasDescription("Right sidebar") }
            val main = scene.descriptionBounds("Session surface")
            val left = scene.descriptionBounds("Left sidebar")
            val right = scene.descriptionBounds("Right sidebar")
            assertTrue(main.width >= 150f && main.height > 0f)
            assertTrue(left.width > 0 && right.width > 0)
            assertTrue(left.right <= main.left && main.right <= right.left)
            assertTrue(right.right <= 320f)
            assertTrue(failures.isEmpty())
            scene.frame()
        } finally { scene.close() }
        assertEquals(0, session.closeCount)
    } }
}

private fun loadingSession(index: Int, name: String): NavigationSession =
    object : NavigationSession(index, name, SidebarTestAgent()) {
        override val lifecycle = MutableStateFlow<PersistedSessionLifecycleState>(PersistedSessionLifecycleState.Loading)
    }.also { it.rootAgent.value = null }

/** UI preference port only; no second Application, backend or filesystem implementation. */
private class RootSidebarPreferences : SidebarSettingsViewModel {
    override val state = MutableStateFlow(SidebarSettings(leftWidth = 12, rightWidth = 12))
    var initialViewport: Int? = null
    val resized = mutableListOf<Pair<SessionSidebarSide, Int>>()
    val selected = mutableListOf<Pair<SessionSidebarSide, SidebarContent>>()
    override fun initializeViewport(columns: Int) { if (initialViewport == null) initialViewport = columns }
    override suspend fun selectLeft(content: SidebarContent) { selected += SessionSidebarSide.Left to content; state.value = state.value.copy(left = content) }
    override suspend fun selectRight(content: SidebarContent) { selected += SessionSidebarSide.Right to content; state.value = state.value.copy(right = content) }
    override suspend fun resizeLeft(columns: Int) { resized += SessionSidebarSide.Left to columns; state.value = state.value.copy(leftWidth = columns) }
    override suspend fun resizeRight(columns: Int) { resized += SessionSidebarSide.Right to columns; state.value = state.value.copy(rightWidth = columns) }
}

private fun OffscreenScene.descriptionBounds(description: String) = nodes().first {
    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true
}.boundsInWindow

private fun OffscreenScene.contextTabText(label: String) {
    val position = text(label).boundsInWindow.center
    scene.sendPointerEvent(PointerEventType.Press, position, buttons = PointerButtons(isSecondaryPressed = true), button = PointerButton.Secondary)
    scene.sendPointerEvent(PointerEventType.Release, position, buttons = PointerButtons(), button = PointerButton.Secondary)
}

private suspend fun OffscreenScene.dragRootSplitter(delta: Float) {
    val start = descriptionBounds("Resize left sidebar").center
    scene.sendPointerEvent(PointerEventType.Press, start, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
    advance()
    val end = start + Offset(delta, 0f)
    scene.sendPointerEvent(PointerEventType.Move, end, buttons = PointerButtons(isPrimaryPressed = true))
    settle { descriptionBounds("Resize left sidebar").center.x > start.x }
    scene.sendPointerEvent(PointerEventType.Release, end, buttons = PointerButtons(), button = PointerButton.Primary)
}
