@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.agent.contract.AgentHistoryActionState
import io.github.stream29.kodex.app.application.contract.*
import io.github.stream29.kodex.cli.history.AgentHistorySource
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.app.history.contract.HistoryStreamingItem
import io.github.stream29.kodex.app.history.contract.HistoryStreamingKind
import io.github.stream29.kodex.cli.history.createAgentHistoryViewModel
import io.github.stream29.kodex.app.session.contract.*
import io.github.stream29.kodex.app.ui.compose.ComposeApplicationScreen
import io.github.stream29.kodex.app.ui.UiActionRole
import io.github.stream29.kodex.app.ui.UiRenderer
import io.github.stream29.kodex.app.ui.UiSubmitKey
import io.github.stream29.kodex.app.ui.UiTextRole
import io.github.stream29.kodex.app.ui.WithUiRenderer
import io.github.stream29.kodex.cli.app.SessionSidebarSide
import io.github.stream29.kodex.cli.settings.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import kotlin.test.*

val offscreenGraphicalRootTest by testSuite {
    test("assistant display modes retain exact owner and reject stale menus during running") { runtimeScene { _, runtime, owner ->
        val firstBase = pageAgent("markdown-first", runtime, owner)
        val secondBase = pageAgent("markdown-second", runtime, owner)
        val firstStorage = InMemoryKodexAgentStorage.empty()
        val secondStorage = InMemoryKodexAgentStorage.empty()
        firstStorage.index[1] = StableAssistantMessage(listOf(ContentItem.OutputText("# Alpha heading\n\n**Alpha result**")))
        secondStorage.index[1] = StableAssistantMessage(listOf(ContentItem.OutputText("# Beta heading\n\n**Beta result**")))
        val firstMaximum = MutableStateFlow(1)
        val firstHistory = createAgentHistoryViewModel(
            AgentHistorySource(firstStorage, firstMaximum, MutableStateFlow(KodexAgentStateValue.UserMessage)),
            owner, MutableStateFlow(false),
        )
        val secondHistory = createAgentHistoryViewModel(
            AgentHistorySource(secondStorage, MutableStateFlow(1), MutableStateFlow(KodexAgentStateValue.UserMessage)),
            owner, MutableStateFlow(false),
        )
        val streaming = MutableStateFlow<HistoryStreamingItem?>(null)
        val firstAgent = object : AgentViewModel by firstBase {
            override val running = MutableStateFlow(true)
            override val historyAction = MutableStateFlow<AgentHistoryActionState>(AgentHistoryActionState.None)
            override val history = object : AgentHistoryViewModel by firstHistory { override val streamingItem = streaming }
        }
        val secondAgent = object : AgentViewModel by secondBase {
            override val running = MutableStateFlow(true)
            override val historyAction = MutableStateFlow<AgentHistoryActionState>(AgentHistoryActionState.None)
            override val history = secondHistory
        }
        val first = object : NavigationSession(51, "first markdown", firstAgent) {
            override val lifecycle = MutableStateFlow<PersistedSessionLifecycleState>(PersistedSessionLifecycleState.Open)
        }
        val second = object : NavigationSession(52, "second markdown", secondAgent) {
            override val lifecycle = MutableStateFlow<PersistedSessionLifecycleState>(PersistedSessionLifecycleState.Open)
        }
        val app = object : NavigationApplication(listOf(first, second)) {
            override val popup = MutableStateFlow<ApplicationPopupState>(ApplicationPopupState.Closed)
        }
        app.selectTab(first)
        val failures = mutableListOf<Throwable>()
        val sidebars = RootSidebarPreferences()
        val newLineKey = MutableStateFlow(NewLineKey.Enter)
        val scene = OffscreenScene.create(width = 900, height = 800) {
            WithUiRenderer(RasterRootRenderer) {
                ComposeApplicationScreen(app, newLineKey, sidebars, failures::add, { false })
            }
        }
        suspend fun openHistoryMenu() {
            scene.settle("stored history row ready") { scene.hasDescription("History entry 1") }
            scene.contextDescription("History entry 1")
        }
        suspend fun openStreamingMenu() {
            scene.settle("streaming history row ready") { scene.hasDescription("Streaming assistant message") }
            scene.contextDescription("Streaming assistant message")
        }
        try {
            scene.settle("first rendered") { scene.hasText("Alpha heading") && scene.hasText("Alpha result") }
            openHistoryMenu()
            scene.settle { scene.hasText("Show raw") }
            assertFalse(scene.hasText("Revert to here"))
            assertFalse(scene.hasText("Fork from here"))
            scene.click("Show raw")
            scene.settle { scene.hasText("# Alpha heading\n\n**Alpha result**") }
            openHistoryMenu()
            scene.settle { scene.hasText("Show rendered") }
            val retained = assertNotNull(scene.nodes().first {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Application menu action: history-display-mode") == true
            }.config.getOrNull(SemanticsActions.OnClick)?.action)
            app.selectTab(second)
            retained()
            scene.settle("second rendered") { scene.hasText("Beta heading") && scene.hasText("Beta result") }
            openHistoryMenu()
            scene.settle { scene.hasText("Show raw") }
            scene.click("Show raw")
            scene.settle { scene.hasText("# Beta heading\n\n**Beta result**") }
            app.selectTab(first)
            scene.settle("first choice retained") { scene.hasText("# Alpha heading\n\n**Alpha result**") }

            val events = MutableSharedFlow<ResponsesStreamEvent>(replay = 8)
            events.emit(ResponsesStreamEvent.OutputItemAdded(0, ResponseItem.Message(
                ResponseItemId("stream-fixture"), MessageRole.Assistant,
                listOf(ContentItem.OutputText("# Streaming heading\n\n**Streaming result**")),
            )))
            streaming.value = HistoryStreamingItem.Output(HistoryStreamingKind.Message, events)
            scene.settle("stream rendered") { scene.hasText("Streaming heading") && scene.hasText("Streaming result") }
            openStreamingMenu()
            scene.settle { scene.hasText("Show raw") }
            assertFalse(scene.hasText("Revert to here"))
            scene.click("Show raw")
            scene.settle { scene.hasText("# Streaming heading\n\n**Streaming result**") }
            app.selectTab(second)
            scene.settle("second choice retained during stream") { scene.hasText("# Beta heading\n\n**Beta result**") }
            app.selectTab(first)
            scene.settle("stream choice survives remount") { scene.hasText("# Streaming heading\n\n**Streaming result**") }
            openStreamingMenu()
            scene.settle { scene.hasText("Show rendered") }
            val nonAssistant = MutableSharedFlow<ResponsesStreamEvent>(replay = 4)
            nonAssistant.emit(ResponsesStreamEvent.OutputItemAdded(0, ResponseItem.Message(
                ResponseItemId("user-fixture"), MessageRole.User,
                listOf(ContentItem.InputText("# User **raw**")),
            )))
            streaming.value = HistoryStreamingItem.Output(HistoryStreamingKind.Message, nonAssistant)
            scene.settle("non-assistant stream remains raw") {
                scene.hasText("# User **raw**") && !scene.hasDescription("Streaming assistant message")
            }
            streaming.value = null
            firstStorage.index[2] = StableAssistantMessage(listOf(ContentItem.OutputText("# Fresh heading\n\n**Fresh result**")))
            firstMaximum.value = 2
            scene.settle("committed message resets display mode") { scene.hasText("Fresh heading") && scene.hasText("Fresh result") }
            assertFalse(scene.hasText("# Fresh heading\n\n**Fresh result**"))
            assertTrue(failures.isEmpty())
        } finally {
            scene.close()
            firstHistory.close()
            secondHistory.close()
            firstBase.closeFixture()
            secondBase.closeFixture()
        }
    } }
    test("sidebar keyboard and accessibility resize exact sides while modal and zero budget preserve preferences") { settingsRootScene { fixture ->
        val session = loadingSession(12, "Keyboard widths")
        val app = object : NavigationApplication(listOf(session)) {
            override val popup = MutableStateFlow<ApplicationPopupState>(ApplicationPopupState.Closed)
        }
        app.navigation.value = app.navigation.value.copy(selection = ApplicationTabSelection.SelectedTab(0))
        val sidebars = RootSidebarPreferences()
        var viewport by mutableStateOf(800)
        val failures = mutableListOf<Throwable>()
        val newLineKey = MutableStateFlow(NewLineKey.Enter)
        val scene = OffscreenScene.create {
            Box(Modifier.width(viewport.dp).height(600.dp)) {
                ComposeApplicationScreen(app, newLineKey, sidebars, failures::add, { false })
            }
        }
        try {
            scene.settle { scene.hasText("Loading Session…") }
            scene.clickDescription("Expand left sidebar")
            scene.clickDescription("Expand right sidebar")
            scene.settle { scene.hasDescription("Resize left sidebar") && scene.hasDescription("Resize right sidebar") }
            fun resizeNode(side: String) = scene.nodes().single {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Resize $side sidebar") == true
            }
            assertTrue(assertNotNull(resizeNode("left").config.getOrNull(SemanticsActions.RequestFocus)?.action).invoke())
            scene.key(Key.DirectionRight)
            scene.settle { sidebars.state.value.leftWidth == 13 }
            assertEquals(SessionSidebarSide.Left to 13, sidebars.resized.last())
            assertTrue(assertNotNull(resizeNode("right").config.getOrNull(SemanticsActions.RequestFocus)?.action).invoke())
            scene.key(Key.DirectionLeft)
            scene.settle { sidebars.state.value.rightWidth == 13 }
            assertEquals(SessionSidebarSide.Right to 13, sidebars.resized.last())
            val retainedResize = assertNotNull(resizeNode("left").config.getOrNull(SemanticsActions.SetProgress)?.action)
            assertTrue(retainedResize(15f))
            scene.settle { sidebars.state.value.leftWidth == 15 }
            assertFalse(retainedResize(Float.NaN))
            val before = sidebars.resized.toList()
            val beforeDrag = scene.descriptionBounds("Left sidebar").width
            val start = scene.descriptionBounds("Resize left sidebar").center
            scene.scene.sendPointerEvent(PointerEventType.Press, start,
                buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.advance()
            scene.scene.sendPointerEvent(PointerEventType.Move, start + Offset(48f, 0f),
                buttons = PointerButtons(isPrimaryPressed = true))
            scene.settle { scene.descriptionBounds("Left sidebar").width > beforeDrag }
            assertEquals(before, sidebars.resized, "Drag preview is not a preference commit.")
            app.popup.value = ApplicationPopupState.Settings(null, fixture.root)
            scene.settle { scene.hasText("Input") && resizeNode("left").config.getOrNull(SemanticsProperties.Disabled) != null }
            scene.scene.sendPointerEvent(PointerEventType.Release, start + Offset(48f, 0f),
                buttons = PointerButtons(), button = PointerButton.Primary)
            assertFalse(retainedResize(20f))
            assertEquals(before, sidebars.resized)
            assertEquals(beforeDrag, scene.descriptionBounds("Left sidebar").width, "Modal cancellation clears preview without committing.")
            app.popup.value = ApplicationPopupState.Closed
            scene.settle { !scene.hasText("Input") }
            val original = sidebars.state.value
            val width = scene.descriptionBounds("Left sidebar").width
            viewport = 32
            scene.settle { !scene.hasDescription("Left sidebar") && !scene.hasDescription("Right sidebar") }
            assertTrue(scene.hasDescription("Hide left sidebar") && scene.hasDescription("Hide right sidebar"))
            assertEquals(original, sidebars.state.value)
            viewport = 800
            scene.settle { scene.hasDescription("Left sidebar") && scene.descriptionBounds("Left sidebar").width == width }
            assertEquals(before, sidebars.resized)
            scene.clickDescription("Collapse left sidebar")
            scene.settle { !scene.hasDescription("Left sidebar") && scene.nodes().any {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Expand left sidebar") == true &&
                    it.config.getOrNull(SemanticsProperties.Focused) == true
            } }
            assertTrue(failures.isEmpty())
            scene.frame()
        } finally { scene.close() }
    } }

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

/** Raster-only editor leaf for an offscreen root; actual browser input needs a DOM viewport. */
private object RasterRootRenderer : UiRenderer {
    @Composable override fun UiTheme(content: @Composable () -> Unit) = content()
    @Composable override fun UiColumn(content: @Composable () -> Unit) { Column { content() } }
    @Composable override fun UiActions(content: @Composable () -> Unit) { Row { content() } }
    @Composable override fun UiText(text: String, role: UiTextRole, maximumLines: Int) {
        Text(text, maxLines = maximumLines)
    }
    @Composable override fun UiProse(text: String, role: UiTextRole) { Text(text) }
    @Composable override fun UiAction(
        label: String, onClick: () -> Unit, enabled: Boolean, autoFocus: Boolean, role: UiActionRole,
    ) { Button(onClick, enabled = enabled) { Text(label) } }
    @Composable override fun UiEditor(
        text: String, cursorOffset: Int, onChange: (String, Int) -> Long?,
        onSubmit: (String, Int) -> Unit, label: String, enabled: Boolean, readOnly: Boolean,
        autoFocus: Boolean, multiline: Boolean, maximumLines: Int, submitKey: UiSubmitKey, revision: Long?,
    ) { Text(label) }
    @Composable override fun TextInputFrame(label: String, content: @Composable () -> Unit) = content()
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
