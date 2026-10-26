package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.*
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.agent.contract.*
import io.github.stream29.kodex.app.application.contract.*
import io.github.stream29.kodex.app.session.contract.*
import io.github.stream29.kodex.app.settings.contract.SettingsPage
import io.github.stream29.kodex.app.ui.UiText
import io.github.stream29.kodex.cli.app.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

val offscreenApplicationFrameTest by testSuite {
    test("selected lifecycle is keyed by exact child and the frame never rereads a replaced Agent") { offscreenTest {
        val agent = object : SidebarTestAgent() { override val running = MutableStateFlow(false) }
        val first = object : NavigationSession(1, "first", agent) {
            override val lifecycle = MutableStateFlow<PersistedSessionLifecycleState>(PersistedSessionLifecycleState.Failed("first failure"))
        }.also { it.rootAgent.value = null }
        val second = object : NavigationSession(1, "second", agent) {
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
            scene.settle { frame?.navigation?.selectedSession === second && scene.hasText("Loading Session…") }
            assertTrue(seen.filter { it.first === second }.all { it.second == PersistedSessionLifecycleState.Loading })
            val frozen = assertNotNull(frame)
            first.lifecycle.value = PersistedSessionLifecycleState.Closed
            scene.advance()
            assertTrue(scene.hasText("Loading Session…"))
            second.lifecycle.value = PersistedSessionLifecycleState.Open
            second.rootAgent.value = agent
            scene.settle { frame.selectedAgent === agent && scene.hasText("Agent surface") }
            assertNull(frozen.selectedAgent)
            assertEquals(PersistedSessionLifecycleState.Loading, frozen.selectedLifecycle)
            assertSame(agent, frame.tabs.tabs.single { it.target === second }.agent)
            second.rootAgent.value = null
            second.lifecycle.value = PersistedSessionLifecycleState.Closed
            scene.settle { scene.hasText("Session view closed.") && !scene.hasText("Agent surface") }
            app.navigation.value = app.navigation.value.copy(selection = ApplicationTabSelection.NewSession)
            scene.settle { frame.navigation.selectedSession == null && !scene.hasText("Session view closed.") }
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
        val suggestion = mutableListOf<Pair<AgentViewModel, String>>()
        val settings = mutableListOf<Pair<PersistedSessionViewModel?, SettingsPage>>()
        val gate = CompletableDeferred<Unit>()
        val failure = IllegalStateException("directory unavailable in root fixture")
        val app = object : NavigationApplication(listOf(oldSession)) {
            override suspend fun openWorkingDirectoryPopup(target: AgentSettingsViewModel): ApplicationPopupState.WorkingDirectory {
                directory += target
                gate.await()
                throw failure
            }
            override suspend fun openSuggestedWorkingDirectoryPopup(target: AgentViewModel, callId: String): ApplicationPopupState.WorkingDirectory? {
                suggestion += target to callId
                throw CancellationException("caller wait only")
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
            captured.browseSuggestedWorkingDirectory(oldAgent, "exact-call")
            captured.openSettings(oldSession)
            captured.openSettings(null)
            scene.settle { settings.size == 2 && suggestion.size == 1 }
            gate.complete(Unit)
            scene.settle { newFailures.size == 1 }
            assertSame(oldAgent, directory.single())
            assertSame(oldAgent, suggestion.single().first)
            assertEquals("exact-call", suggestion.single().second)
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
