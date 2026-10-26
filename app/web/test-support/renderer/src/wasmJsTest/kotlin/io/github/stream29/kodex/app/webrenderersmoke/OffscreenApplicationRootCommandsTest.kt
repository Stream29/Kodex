package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.*
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.agent.contract.*
import io.github.stream29.kodex.app.application.contract.*
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemState
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.sessionrename.createSessionRenameViewModel
import io.github.stream29.kodex.app.sessionrename.contract.SessionRenameViewModel
import io.github.stream29.kodex.app.sessiontabbar.contract.SessionTabMenuPlacement
import io.github.stream29.kodex.app.ui.UiText
import io.github.stream29.kodex.app.ui.compose.ComposeHistoryViewState
import io.github.stream29.kodex.cli.app.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

val offscreenApplicationRootCommandsTest by testSuite {
    test("root tab commands admit the exact opening once and share failure and cancellation routing") { offscreenTest {
        val session = NavigationSession(19, "captured", SidebarTestAgent())
        val calls = mutableListOf<Pair<String, Any>>()
        var closeFailure: Throwable? = null
        val app = object : NavigationApplication(listOf(session)) {
            override suspend fun closeTab(target: PersistedSessionViewModel): Boolean {
                calls += "close" to target
                closeFailure?.let { throw it }
                return true
            }
            override suspend fun closeAndArchiveSession(target: PersistedSessionViewModel): Boolean {
                calls += "archive" to target
                return true
            }
            override suspend fun openRenameSessionPopup(target: PersistedSessionViewModel): ApplicationPopupState.RenameSession {
                calls += "rename" to target
                throw CancellationException("command observation only")
            }
            override suspend fun openDeleteSessionPopup(sessionIndex: Int): ApplicationPopupState.DeleteSession {
                calls += "delete" to sessionIndex
                throw CancellationException("command observation only")
            }
        }
        val placement = object : SessionTabMenuPlacement {}
        fun next() = ApplicationTabMenuTarget(session, "captured", placement)
        var opening by mutableStateOf<ApplicationTabMenuTarget?>(next())
        val renderer = RootCommandSlots()
        val failures = mutableListOf<Throwable>()
        val scene = OffscreenScene.create {
            ApplicationMenusContent(
                app, app.navigation.collectAsState().value, null, opening, null, renderer,
                admitTab = { if (opening === it) { opening = null; true } else false },
                admitHistory = { false }, focusEditedComposer = { error("no editor") },
                onOperationFailure = failures::add,
            )
        }
        try {
            scene.settle { renderer.tab.isNotEmpty() }
            val oldClose = renderer.tab.action("close")
            opening = next()
            scene.advance()
            oldClose.onClick()
            scene.advance()
            assertTrue(calls.isEmpty())
            for ((key, label) in listOf("rename" to "rename", "close-and-archive" to "archive", "delete" to "delete")) {
                val request = next()
                opening = request
                scene.settle { renderer.renderedTab === request.placement && scene.hasText("Tab command slot") }
                val callback = renderer.tab.action(key)
                callback.onClick()
                callback.onClick()
                scene.settle { calls.lastOrNull()?.first == label && opening == null }
            }
            val failure = IllegalStateException("ordinary close failure")
            for (thrown in listOf(failure, CancellationException("caller wait stopped"))) {
                closeFailure = thrown
                opening = next()
                scene.settle { scene.hasText("Tab command slot") }
                renderer.tab.action("close").onClick()
                scene.settle { calls.count { it.first == "close" } == if (thrown === failure) 1 else 2 }
            }
            scene.advance()
            assertEquals<List<Throwable>>(listOf(failure), failures)
            assertEquals(listOf("rename", "archive", "delete", "close", "close"), calls.map { it.first })
            calls.filter { it.first != "delete" }.forEach { assertSame(session, it.second) }
            assertEquals(19, calls.single { it.first == "delete" }.second)
            scene.frame()
        } finally { scene.close() }
        assertEquals(0, session.closeCount + app.closeCount)
    } }
    test("history commands preserve boundary and owner while navigation cannot redirect draft or focus") { runtimeScene { _, runtime, owner ->
        val actual = pageAgent("root command", runtime, owner)
        val requests = mutableListOf<Pair<Int, Long>>()
        val confirms = mutableListOf<Pair<Int, Long>>()
        var gate = CompletableDeferred<Unit>()
        var failure: Throwable? = null
        val agent = object : AgentViewModel by actual {
            override val historyAction = MutableStateFlow<AgentHistoryActionState>(AgentHistoryActionState.None)
            override suspend fun revertHistory(untilExclusive: Int, expectedGeneration: Long) {
                requests += untilExclusive to expectedGeneration
                gate.await()
                failure?.let { throw it }
            }
            override fun requestHistoryRevert(untilExclusive: Int, expectedGeneration: Long): Long {
                confirms += untilExclusive to expectedGeneration
                return 1
            }
        }
        val forks = mutableListOf<Triple<AgentViewModel, Int, Long>>()
        val session = object : NavigationSession(23, "captured", agent) {
            override suspend fun fork(source: AgentViewModel, untilExclusive: Int, expectedGeneration: Long): Int {
                forks += Triple(source, untilExclusive, expectedGeneration)
                return 99
            }
        }
        val opened = mutableListOf<Int>()
        val rename = createSessionRenameViewModel("captured") { error("No rename command in focus fixture") }
        val popup = ApplicationPopupState.RenameSession(object : RenameSessionPopupViewModel, SessionRenameViewModel by rename {
            override val target = session
        })
        val app = object : NavigationApplication(listOf(session)) {
            override val popup = MutableStateFlow<ApplicationPopupState>(ApplicationPopupState.Closed)
            override suspend fun openSession(sessionIndex: Int): PersistedSessionViewModel { opened += sessionIndex; return session }
        }
        app.navigation.value = ApplicationNavigationState(listOf(session), ApplicationTabSelection.SelectedTab(0))
        var opening by mutableStateOf<ApplicationHistoryMenuOpening?>(null)
        val renderer = RootCommandSlots()
        val focused = mutableListOf<AgentViewModel>()
        val failures = mutableListOf<Throwable>()
        val scene = OffscreenScene.create {
            val navigation = app.navigation.collectAsState().value
            val selected = navigation.selectedSession
            val child = selected?.rootAgent?.collectAsState()?.value
            ApplicationMenusContent(
                app, navigation, child, null, opening, renderer, { false },
                admitHistory = { if (opening === it) { opening = null; true } else false },
                focusEditedComposer = focused::add, onOperationFailure = failures::add,
            )
        }
        try {
            scene.settle { agent.history.historyItems.value.size > 0 }
            val window = agent.history.historyItems.value
            val item = assertIs<MessageHistoryItemViewModel>(window[0])
            scene.settle { item.state.value is MessageHistoryItemState.Ready }
            fun next() = ApplicationHistoryMenuOpening(session, agent, window.generation, 1, item, object : ApplicationHistoryMenuPlacement {})
            suspend fun open() {
                opening = next()
                scene.settle { scene.hasText("History command slot") && renderer.history.any { it.key == "revert-and-edit" } }
            }
            open()
            renderer.history.action("revert-to-here").onClick()
            scene.settle { confirms == listOf(2 to window.generation) && opening == null }
            open()
            renderer.history.action("fork-from-here").onClick()
            scene.settle { opened == listOf(99) }
            assertSame(agent, forks.single().first)
            assertEquals(Triple(agent, 2, window.generation), forks.single())
            open()
            renderer.history.action("revert-and-edit").onClick()
            scene.settle { requests.size == 1 && opening == null }
            app.navigation.value = app.navigation.value.copy(selection = ApplicationTabSelection.NewSession)
            gate.complete(Unit)
            scene.settle { actual.composer.state.value.text == "History root command" }
            assertTrue(focused.isEmpty())
            app.navigation.value = app.navigation.value.copy(selection = ApplicationTabSelection.SelectedTab(0))
            open()
            renderer.history.action("revert-and-edit").onClick()
            scene.settle { requests.size == 2 && focused.singleOrNull() === agent }
            gate = CompletableDeferred()
            actual.composer.update("modal pending")
            open()
            renderer.history.action("revert-and-edit").onClick()
            scene.settle { requests.size == 3 }
            app.popup.value = popup
            gate.complete(Unit)
            scene.settle { actual.composer.state.value.text == "History root command" }
            scene.advance()
            assertEquals<List<AgentViewModel>>(listOf(agent), focused)
            app.popup.value = ApplicationPopupState.Closed
            val ordinary = IllegalStateException("revert rejected")
            for (thrown in listOf(ordinary, CancellationException("caller wait stopped"))) {
                failure = thrown
                gate = CompletableDeferred<Unit>().also { it.complete(Unit) }
                actual.composer.update("do not replace")
                open()
                renderer.history.action("revert-and-edit").onClick()
                scene.settle { requests.size == if (thrown === ordinary) 4 else 5 }
                scene.advance()
                assertEquals("do not replace", actual.composer.state.value.text)
            }
            assertEquals<List<Throwable>>(listOf(ordinary), failures)
            assertEquals(List(5) { 1 to window.generation }, requests)
            assertEquals<List<AgentViewModel>>(listOf(agent), focused)
            scene.frame()
        } finally {
            try { scene.close(); assertEquals(0, actual.closeCount + actual.historyCloses + session.closeCount) }
            finally { rename.close(); actual.closeFixture() }
        }
    } }
    test("shared tab observation retires hidden History widget state by exact owner") { runtimeScene { _, runtime, owner ->
        val first = pageAgent("selected", runtime, owner)
        val hidden = pageAgent("hidden", runtime, owner)
        val replacement = pageAgent("replacement", runtime, owner)
        val firstTab = NavigationSession(1, "selected", first)
        val hiddenTab = NavigationSession(2, "hidden", hidden)
        val app = NavigationApplication(listOf(firstTab, hiddenTab))
        val states = mutableListOf<Pair<AgentHistoryViewModel, ComposeHistoryViewState>>()
        var tabs: List<SessionTabRenderState> = emptyList()
        val scene = OffscreenScene.create {
            tabs = collectApplicationTabBar(app.navigation.collectAsState().value).tabs
            retainApplicationHistoryWidgetStates(states, tabs.mapNotNull { it.agent?.history })
            tabs.mapNotNull { it.agent?.history }.forEach { applicationHistoryWidgetStateFor(states, it, ::ComposeHistoryViewState) }
            UiText("Retained ${states.size}")
        }
        try {
            scene.settle { states.size == 2 }
            val firstState = applicationHistoryWidgetStateFor(states, first.history, ::ComposeHistoryViewState)
            val hiddenState = applicationHistoryWidgetStateFor(states, hidden.history, ::ComposeHistoryViewState)
            hiddenTab.rootAgent.value = replacement
            scene.settle { tabs.last().agent === replacement && states.none { it.first === hidden.history } }
            assertSame(firstState, applicationHistoryWidgetStateFor(states, first.history, ::ComposeHistoryViewState))
            hiddenTab.rootAgent.value = hidden
            scene.settle { tabs.last().agent === hidden }
            assertNotSame(hiddenState, applicationHistoryWidgetStateFor(states, hidden.history, ::ComposeHistoryViewState))
            app.navigation.value = ApplicationNavigationState(listOf(firstTab), ApplicationTabSelection.NewSession)
            scene.settle { states.size == 1 }
            assertEquals(0, first.closeCount + hidden.closeCount + replacement.closeCount)
            scene.frame()
        } finally {
            try { scene.close() } finally { first.closeFixture(); hidden.closeFixture(); replacement.closeFixture() }
        }
    } }
}

/** Command slots only; real graphical popup geometry and input are covered by the menu scenes. */
private class RootCommandSlots : ApplicationMenuRenderer {
    var tab: List<ApplicationMenuEntry> = emptyList()
    var history: List<ApplicationMenuEntry> = emptyList()
    var renderedTab: SessionTabMenuPlacement? = null
    override fun isTabPlaced(placement: SessionTabMenuPlacement): Boolean = true
    override fun isHistoryPlaced(placement: ApplicationHistoryMenuPlacement): Boolean = true
    @Composable override fun Tab(placement: SessionTabMenuPlacement, entries: List<ApplicationMenuEntry>, onDismiss: () -> Unit) {
        tab = entries; renderedTab = placement
        UiText("Tab command slot")
    }
    @Composable override fun History(placement: ApplicationHistoryMenuPlacement, entries: List<ApplicationMenuEntry>, onDismiss: () -> Unit) {
        history = entries
        UiText("History command slot")
    }
    @Composable override fun RevertConfirmation(
        title: String, paragraphs: List<String>, cancelLabel: String, confirmLabel: String,
        onCancel: () -> Unit, onConfirm: () -> Unit,
    ) { error("No confirmation in command wiring fixture") }
}

private fun List<ApplicationMenuEntry>.action(key: String): ApplicationMenuEntry.Action =
    filterIsInstance<ApplicationMenuEntry.Action>().single { it.key == key }
