package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.*
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.agent.contract.*
import io.github.stream29.kodex.app.application.contract.*
import io.github.stream29.kodex.app.session.contract.*
import io.github.stream29.kodex.app.sessiontabbar.contract.*
import io.github.stream29.kodex.app.settings.contract.SettingsPage
import io.github.stream29.kodex.app.ui.compose.rememberComposeSessionTabBarRenderer
import io.github.stream29.kodex.cli.app.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.*
import kotlin.time.Instant

val offscreenApplicationTabsTest by testSuite {
    test("shared projection follows exact name and Agent observations without merging same-index replacements") { offscreenTest {
        val firstAgent = NavigationAgent()
        val secondAgent = NavigationAgent()
        val first = NavigationSession(1, "Same", firstAgent)
        val second = NavigationSession(2, "Same", secondAgent)
        val replacement = NavigationSession(1, "Replacement", NavigationAgent())
        val app = NavigationApplication(listOf(first, second))
        val probe = NavigationProbe()
        val scene = OffscreenScene.create { NavigationHost(app, probe) }
        try {
            scene.settle { probe.projection?.tabs?.size == 2 && probe.firstSelect != null }
            val original = assertNotNull(probe.projection)
            val firstIdentity = original.state.tabs.first().identity
            val secondIdentity = original.state.tabs.last().identity
            scene.clickDescription("Session tab: ${secondIdentity.value}")
            scene.settle { app.selected.singleOrNull() === second && app.navigation.value.selectedSession === second }
            first.name.value = "Renamed"
            firstAgent.running.value = true
            scene.settle { scene.hasText("Renamed") && scene.hasDescription("Running") }
            assertEquals(firstIdentity, assertNotNull(probe.projection).state.tabs.first().identity)
            val newAgent = NavigationAgent()
            first.rootAgent.value = newAgent
            scene.settle { !scene.hasDescription("Running") }
            firstAgent.running.value = false
            firstAgent.running.value = true
            scene.advance()
            assertFalse(scene.hasDescription("Running"))
            app.navigation.value = ApplicationNavigationState(listOf(second, first), ApplicationTabSelection.SelectedTab(0))
            scene.settle { probe.projection?.tabs?.firstOrNull()?.target === second }
            assertEquals(secondIdentity, assertNotNull(probe.projection).state.tabs.first().identity)
            assertNotNull(probe.firstSelect).invoke()
            scene.settle { app.selected.lastOrNull() === first }
            val placement = object : SessionTabMenuPlacement {}
            assertNotNull(probe.firstMenu).invoke(placement)
            assertNotNull(probe.firstMenu).invoke(placement)
            assertEquals(2, probe.menus.size)
            assertNotEquals(probe.menus[0], probe.menus[1])
            assertSame(first, probe.menus.first().target)
            assertSame(placement, probe.menus.first().placement)
            app.navigation.value = ApplicationNavigationState(listOf(second, replacement), ApplicationTabSelection.NewSession)
            scene.settle { probe.projection?.tabs?.lastOrNull()?.target === replacement }
            val current = assertNotNull(probe.projection)
            assertNull(current.targetFor(firstIdentity))
            val replacementIdentity = current.state.tabs.last().identity
            assertNotEquals(firstIdentity, replacementIdentity)
            val before = app.selected.toList()
            assertNotNull(probe.firstSelect).invoke()
            assertNotNull(probe.firstMenu).invoke(object : SessionTabMenuPlacement {})
            assertEquals(before, app.selected)
            assertEquals(2, probe.menus.size)
            scene.clickDescription("Session tab: ${replacementIdentity.value}")
            scene.settle { app.selected.lastOrNull() === replacement }
            scene.frame()
        } finally { scene.close() }
        assertEquals(0, app.closeCount + first.closeCount + second.closeCount + replacement.closeCount)
        assertEquals(0, firstAgent.closeCount + secondAgent.closeCount)
    } }
    test("navigation intents report an ordinary failure once and do not report or replay cancellation") { offscreenTest {
        val session = NavigationSession(7, "Target", NavigationAgent())
        val app = NavigationApplication(listOf(session))
        val probe = NavigationProbe()
        val scene = OffscreenScene.create { NavigationHost(app, probe) }
        try {
            scene.settle { probe.projection != null }
            val identity = assertNotNull(probe.projection).state.tabs.single().identity
            val failure = IllegalStateException("explicit navigation failure")
            app.selectFailure = failure
            scene.clickDescription("Session tab: ${identity.value}")
            scene.settle { probe.failures.size == 1 }
            assertSame(failure, probe.failures.single())
            assertEquals(1, app.selected.size)
            app.selectFailure = CancellationException("caller wait stopped")
            scene.clickDescription("Session tab: ${identity.value}")
            scene.settle { app.selected.size == 2 }
            scene.advance()
            assertEquals(1, probe.failures.size)
            scene.clickDescription("New Session")
            scene.settle { app.newSessionIntents == 1 }
            scene.click("Sessions")
            scene.settle { app.catalogIntents == 1 && probe.failures.size == 2 }
            assertSame(app.catalogFailure, probe.failures.last())
            assertEquals(2, probe.beforeIntents)
            scene.advance()
            assertEquals(2, app.selected.size)
            assertEquals(1, app.catalogIntents)
            scene.frame()
        } finally { scene.close() }
        assertEquals(0, app.closeCount + session.closeCount)
    } }
}

@Composable
private fun NavigationHost(app: NavigationApplication, probe: NavigationProbe) {
    val navigation = app.navigation.collectAsState().value
    val projection = collectApplicationTabBar(navigation)
    probe.projection = projection
    val graphical = rememberComposeSessionTabBarRenderer()
    val renderer = object : SessionTabBarRenderer by graphical {
        @Composable
        override fun Tab(
            tab: SessionTabPresentation, selected: Boolean, first: Boolean,
            onSelect: () -> Unit, onMenu: (SessionTabMenuPlacement) -> Unit,
        ) {
            if (first && probe.firstSelect == null) { probe.firstSelect = onSelect; probe.firstMenu = onMenu }
            graphical.Tab(tab, selected, first, onSelect, onMenu)
        }
    }
    ApplicationTabBarContent(
        app, navigation, projection, renderer, probe.failures::add, probe.menus::add,
        onBeforeNewOrCatalog = { probe.beforeIntents++ },
    )
}

private class NavigationProbe {
    var projection: ApplicationTabBarProjection? = null
    var firstSelect: (() -> Unit)? = null
    var firstMenu: ((SessionTabMenuPlacement) -> Unit)? = null
    val failures = mutableListOf<Throwable>()
    val menus = mutableListOf<ApplicationTabMenuTarget>()
    var beforeIntents = 0
}

private class NavigationAgent : SidebarTestAgent() {
    override val running = MutableStateFlow(false)
}

/** Only observations used by tab projection are supplied; unexpected Session work fails. */
internal open class NavigationSession(
    override val sessionIndex: Int, name: String, agent: AgentViewModel,
) : PersistedSessionViewModel, AgentSettingsViewModel by agent {
    override val name = MutableStateFlow(name)
    override val rootAgent = MutableStateFlow<AgentViewModel?>(agent)
    override val lifecycle: StateFlow<PersistedSessionLifecycleState> get() = unused()
    var closeCount = 0
    override fun close() { closeCount++ }
    override suspend fun rename(name: String): Unit = unused()
    override suspend fun refresh(): Unit = unused()
    override suspend fun readCreatedAt(): Instant? = unused()
    override suspend fun readUpdatedAt(): Instant? = unused()
    override suspend fun fork(source: AgentViewModel, untilExclusive: Int, expectedGeneration: Long): Int = unused()
    override suspend fun fork(): Int = unused()
    override suspend fun shutdown(): Unit = unused()
    private fun unused(): Nothing = error("Unexpected Session API in navigation wiring fixture")
}

/** Command/observation probe, not an alternate Application implementation or backend fixture. */
internal open class NavigationApplication(tabs: List<PersistedSessionViewModel>) : ApplicationViewModel {
    override val navigation = MutableStateFlow(ApplicationNavigationState(tabs, ApplicationTabSelection.NewSession))
    override val newSession: NewSessionViewModel get() = unused()
    override val popup: StateFlow<ApplicationPopupState> get() = unused()
    val selected = mutableListOf<PersistedSessionViewModel>()
    var selectFailure: Throwable? = null
    var newSessionIntents = 0
    var catalogIntents = 0
    val catalogFailure = IllegalStateException("Catalog fixture port failed")
    var closeCount = 0
    override suspend fun selectTab(target: PersistedSessionViewModel): Boolean {
        selected += target
        selectFailure?.let { throw it }
        val current = navigation.value
        val index = current.tabs.indexOfFirst { it === target }
        if (index < 0) return false
        navigation.value = current.copy(selection = ApplicationTabSelection.SelectedTab(index))
        return true
    }
    override suspend fun selectNewSession() {
        newSessionIntents++
        navigation.value = navigation.value.copy(selection = ApplicationTabSelection.NewSession)
    }
    override suspend fun openSessionCatalogPopup(): ApplicationPopupState.SessionCatalog {
        catalogIntents++
        throw catalogFailure
    }
    override fun close() { closeCount++ }
    override suspend fun openSession(sessionIndex: Int): PersistedSessionViewModel = unused()
    override suspend fun closeTab(target: PersistedSessionViewModel): Boolean = unused()
    override suspend fun closeAndArchiveSession(target: PersistedSessionViewModel): Boolean = unused()
    override suspend fun deleteSession(sessionIndex: Int): Boolean = unused()
    override suspend fun forkSession(sessionIndex: Int): Int = unused()
    override suspend fun openCreatedSessions(sessionIndexes: List<Int>): Unit = unused()
    override suspend fun submitNewSession(expectedRevision: Long): PersistedSessionViewModel? = unused()
    override suspend fun openSettingsPopup(target: PersistedSessionViewModel?, initialPage: SettingsPage): ApplicationPopupState.Settings = unused()
    override suspend fun openRenameSessionPopup(target: PersistedSessionViewModel): ApplicationPopupState.RenameSession = unused()
    override suspend fun openDeleteSessionPopup(sessionIndex: Int): ApplicationPopupState.DeleteSession = unused()
    override suspend fun openLoginPopup(returnTo: ApplicationPopupState.Settings): ApplicationPopupState.Login = unused()
    override suspend fun openWorkingDirectoryPopup(target: AgentSettingsViewModel): ApplicationPopupState.WorkingDirectory = unused()
    override suspend fun openSuggestedWorkingDirectoryPopup(target: AgentViewModel, callId: String): ApplicationPopupState.WorkingDirectory? = unused()
    override fun dismissPopup(expected: ApplicationPopupState.Open): Boolean = unused()
    override suspend fun shutdown(): Unit = unused()
    private fun unused(): Nothing = error("Unexpected Application API in navigation wiring fixture")
}
