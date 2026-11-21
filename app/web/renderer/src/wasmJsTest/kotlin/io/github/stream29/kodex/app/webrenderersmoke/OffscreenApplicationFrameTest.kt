package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.*
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.agent.contract.*
import io.github.stream29.kodex.app.application.contract.*
import io.github.stream29.kodex.app.session.contract.*
import io.github.stream29.kodex.app.settings.contract.SettingsPage
import io.github.stream29.kodex.app.ui.UiText
import io.github.stream29.kodex.cli.app.*
import io.github.stream29.kodex.cli.historyindex.HistoryIndexParentAnchor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlin.test.*

val offscreenApplicationFrameTest by testSuite {
    test("a retained child page action cannot become current again after leaving its mount") { offscreenTest {
        val a = NavigationAgent()
        val b = NavigationAgent()
        val tab = NavigationAgentTab(12, "child A", a)
        val other = NavigationAgentTab(13, "child B", b)
        val opened = mutableListOf<AgentViewModel>()
        val app = object : NavigationApplication(listOf(tab, other)) {
            override val popup = MutableStateFlow<ApplicationPopupState>(ApplicationPopupState.Closed)
            override suspend fun openAgentSettingsPopup(target: AgentPageActionTarget): ApplicationPopupState.Settings? {
                if (target.isCurrent()) {
                    assertSame(navigation.value.selectedTab, target.tab)
                    opened += target.agent
                }
                return null
            }
        }
        app.navigation.value = app.navigation.value.copy(selection = ApplicationTabSelection.SelectedTab(0))
        lateinit var page: AgentPageActionTarget
        lateinit var rootActions: ApplicationRootActions
        val scene = OffscreenScene.create {
            rootActions = rememberApplicationRootActions(app) { throw IllegalStateException("Unexpected action failure", it) }
            val frame = collectApplicationFrame(app)
            ApplicationPersistedSurfaceContent(frame, { selected ->
                page = rememberAgentPageActionTarget(assertNotNull(frame.navigation.selectedTab), selected)
                assertSame(a, frame.tabs.tabs.first().agent)
                assertSame(b, frame.tabs.tabs.last().agent)
                UiText("Agent page")
            }, { UiText(it) })
        }
        try {
            scene.settle("initial child mount") { scene.hasText("Agent page") && page.agent === a && page.isCurrent() }
            val first = page
            rootActions.openAgentSettings(first)
            scene.settle("first child settings action") { opened.size == 1 }
            app.selectTab(other)
            scene.settle("replacement child mount") { page.agent === b && page.isCurrent() }
            assertFalse(first.isCurrent())
            rootActions.openAgentSettings(first)
            rootActions.openAgentSettings(page)
            scene.settle("replacement child settings action") { opened.size == 2 }
            assertEquals(listOf<AgentViewModel>(a, b), opened)
            app.selectTab(tab)
            scene.settle("remounted first child") { page.agent === a && page.isCurrent() }
            assertFalse(first.isCurrent())
            assertSame(a, tab.agent.value)
        } finally { scene.close() }
    } }

    test("closing the parent stops completion observation but keeps its return action") { offscreenTest {
        val completion = MutableStateFlow(false)
        var observations = 0
        var disposals = 0
        val child = NavigationAgentTab(42, "Child", NavigationAgent())
        val parent = object : NavigationSession(1, "Parent", NavigationAgent()) {
            override fun observeCompletionAnchor(childAgentId: Int) = flow {
                assertEquals(child.agentId, childAgentId)
                observations++
                try { emitAll(completion) } finally { disposals++ }
            }
        }
        val returned = mutableListOf<AgentParentAnchor>()
        val app = object : NavigationApplication(listOf(parent, child)) {
            override val popup = MutableStateFlow<ApplicationPopupState>(ApplicationPopupState.Closed)
            override suspend fun returnToParent(source: AgentPageActionTarget, anchor: AgentParentAnchor) {
                assertSame(child, source.tab)
                returned += anchor
            }
        }
        app.navigation.value = ApplicationNavigationState(
            listOf(parent, child), ApplicationTabSelection.SelectedTab(1), mapOf(child.agentId to parent.agentId),
        )
        var returnToParent: (() -> Unit)? = null
        val scene = OffscreenScene.create {
            val frame = collectApplicationFrame(app)
            val index = rememberApplicationIndexNavigation(app, frame) { throw it }
            val navigate = index?.returnToParent
            if (index != null && navigate != null) {
                // Same provider/actions used by both actual sidebar renderers.
                returnToParent = { navigate(HistoryIndexParentAnchor.Creation) }
                UiText(if (index.hasCompletionAnchor()) "Matched completion" else "Return to parent")
            }
        }
        try {
            scene.settle { observations == 1 && scene.hasText("Return to parent") }
            completion.value = true
            scene.settle { scene.hasText("Matched completion") }
            app.navigation.value = app.navigation.value.copy(
                tabs = listOf(child), selection = ApplicationTabSelection.SelectedTab(0),
            )
            scene.settle { disposals == 1 && scene.hasText("Return to parent") }
            assertFalse(scene.hasText("Matched completion"))
            assertEquals(1, observations)
            assertNotNull(returnToParent).invoke()
            scene.settle { returned == listOf(AgentParentAnchor.Creation) }
        } finally { scene.close() }
    } }

    test("selected lifecycle is keyed by exact child and the frame never rereads a replaced Agent") { offscreenTest {
        val agent = object : SidebarTestAgent() { override val running = MutableStateFlow(false) }
        val first = object : NavigationSession(1, "first", agent) {
            override val lifecycle = MutableStateFlow<PersistedSessionLifecycleState>(PersistedSessionLifecycleState.Failed("first failure"))
        }.also { it.rootAgent.value = null }
        val second = object : NavigationSession(2, "second", agent) {
            override val lifecycle = MutableStateFlow<PersistedSessionLifecycleState>(PersistedSessionLifecycleState.Loading)
        }.also { it.rootAgent.value = null }
        val app = object : NavigationApplication(listOf(first, second)) {
            override val popup = MutableStateFlow<ApplicationPopupState>(ApplicationPopupState.Closed)
        }
        app.navigation.value = app.navigation.value.copy(selection = ApplicationTabSelection.SelectedTab(0))
        var frame: ApplicationFrame? = null
        val seen = mutableListOf<Pair<PersistedSessionViewModel?, PersistedSessionLifecycleState?>>()
        val scene = OffscreenScene.create {
            val current = collectApplicationFrame(app)
            frame = current
            seen += current.navigation.selectedSession to current.selectedLifecycle
            UiText("Root frame")
            ApplicationPersistedSurfaceContent(current, { UiText("Agent surface") }, { UiText(it) })
        }
        try {
            scene.settle { scene.hasText("first failure") }
            app.navigation.value = app.navigation.value.copy(selection = ApplicationTabSelection.SelectedTab(1))
            scene.settle { frame?.navigation?.selectedSession === second && scene.hasText("Loading Agent…") }
            assertTrue(seen.filter { it.first === second }.all { it.second == PersistedSessionLifecycleState.Loading })
            val frozen = assertNotNull(frame)
            first.lifecycle.value = PersistedSessionLifecycleState.Closed
            scene.advance()
            assertTrue(scene.hasText("Loading Agent…"))
            second.lifecycle.value = PersistedSessionLifecycleState.Open
            second.rootAgent.value = agent
            scene.settle { frame.selectedAgent === agent && scene.hasText("Agent surface") }
            assertNull(frozen.selectedAgent)
            assertEquals(PersistedSessionLifecycleState.Loading, frozen.selectedLifecycle)
            assertSame(agent, frame.tabs.tabs.single { it.target === second }.agent)
            second.rootAgent.value = null
            second.lifecycle.value = PersistedSessionLifecycleState.Closed
            scene.settle { scene.hasText("Agent view closed.") && !scene.hasText("Agent surface") }
            app.navigation.value = app.navigation.value.copy(selection = ApplicationTabSelection.NewSession)
            scene.settle { frame.navigation.selectedSession == null && !scene.hasText("Agent view closed.") }
            scene.frame()
        } finally { scene.close() }
        assertEquals(0, first.closeCount + second.closeCount + agent.closeCount + app.closeCount)
    } }
    test("root settings and directory actions keep captured targets and report only ordinary failure to the latest outlet") { offscreenTest {
        val oldAgent = SidebarTestAgent()
        val newAgent = SidebarTestAgent()
        val oldSession = NavigationSession(7, "old", oldAgent)
        val replacement = NavigationSession(7, "new", newAgent)
        val directory = mutableListOf<AgentSettingsViewModel>()
        val settings = mutableListOf<Pair<PersistedSessionViewModel?, SettingsPage>>()
        val gate = CompletableDeferred<Unit>()
        val failure = IllegalStateException("directory unavailable in root fixture")
        val app = object : NavigationApplication(listOf(oldSession)) {
            override suspend fun openWorkingDirectoryPopup(target: AgentSettingsViewModel): ApplicationPopupState.WorkingDirectory {
                directory += target
                gate.await()
                throw failure
            }
            override suspend fun openSettingsPopup(target: PersistedSessionViewModel?, initialPage: SettingsPage): ApplicationPopupState.Settings {
                settings += target to initialPage
                throw CancellationException("command observation only")
            }
        }
        var actions: ApplicationRootActions? = null
        var newerOutlet by mutableStateOf(false)
        val oldFailures = mutableListOf<Throwable>()
        val newFailures = mutableListOf<Throwable>()
        val scene = OffscreenScene.create {
            val outlet = if (newerOutlet) newFailures else oldFailures
            actions = rememberApplicationRootActions(app) { outlet.add(it) }
            UiText(if (newerOutlet) "New outlet" else "Old outlet")
        }
        try {
            scene.settle { actions != null }
            val captured = assertNotNull(actions)
            captured.browseWorkingDirectory(oldAgent)
            scene.settle { directory.size == 1 }
            app.navigation.value = ApplicationNavigationState(listOf(replacement), ApplicationTabSelection.SelectedTab(0))
            newerOutlet = true
            scene.settle { scene.hasText("New outlet") }
            captured.openSettings(oldSession)
            captured.openSettings(null)
            scene.settle { settings.size == 2 }
            gate.complete(Unit)
            scene.settle { newFailures.size == 1 }
            assertSame(oldAgent, directory.single())
            assertSame(oldSession, settings.first().first)
            assertEquals(SettingsPage.CurrentSession, settings.first().second)
            assertNull(settings.last().first)
            assertEquals(SettingsPage.NewSession, settings.last().second)
            assertTrue(oldFailures.isEmpty())
            assertSame(failure, newFailures.single())
            scene.advance()
            assertEquals(1, directory.size)
            assertEquals(2, settings.size)
            scene.frame()
        } finally { scene.close() }
        assertEquals(0, oldAgent.closeCount + newAgent.closeCount + oldSession.closeCount + replacement.closeCount)
    } }
}
