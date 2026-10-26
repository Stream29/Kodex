package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.agent.contract.*
import io.github.stream29.kodex.app.ui.UiText
import io.github.stream29.kodex.app.ui.compose.*
import io.github.stream29.kodex.cli.agent.*
import io.github.stream29.kodex.cli.app.*
import io.github.stream29.kodex.cli.settings.NewLineKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*
import kotlin.time.Instant

val offscreenApplicationMenusTest by testSuite {
    test("real tab origin retains keyboard focus across optional metadata and closes on source unmount") { offscreenTest {
        val created = CompletableDeferred<Instant>()
        var reads = 0
        val session = object : NavigationSession(42, "Menu target", object : SidebarTestAgent() {
            override val running = MutableStateFlow(false)
        }) {
            override suspend fun readCreatedAt(): Instant { reads++; return created.await() }
            override suspend fun readUpdatedAt(): Instant? = error("optional read failure")
        }
        val app = NavigationApplication(listOf(session))
        var opening by mutableStateOf<ApplicationTabMenuTarget?>(null)
        var sourceMounted by mutableStateOf(true)
        var sourceOffset by mutableStateOf(0)
        var tabDescription = ""
        val calls = mutableListOf<ApplicationTabMenuTarget>()
        val failures = mutableListOf<Throwable>()
        val scene = OffscreenScene.create {
            val navigation = app.navigation.collectAsState().value
            val tabs = collectApplicationTabBar(navigation)
            tabDescription = "Session tab: ${tabs.state.tabs.single().identity.value}"
            Spacer(Modifier.height(sourceOffset.dp))
            if (sourceMounted) ApplicationTabBarContent(
                app, navigation, tabs, rememberComposeSessionTabBarRenderer(), failures::add, { opening = it },
            )
            ApplicationTabMenuContent(
                opening, true, rememberComposeApplicationMenuRenderer(),
                onDismiss = { if (opening === it) opening = null },
                onClose = { if (opening === it) { calls += it; opening = null } },
                onCloseAndArchive = { error("unexpected archive") },
                onRename = { error("unexpected rename") }, onDelete = { error("unexpected delete") },
            )
        }
        try {
            scene.settle { scene.hasText("Menu target") }
            scene.contextDescription(tabDescription)
            scene.settle { scene.hasText("Index: 42") && scene.menuFocused("rename") }
            val first = assertNotNull(opening)
            assertEquals(1, reads)
            scene.key(Key.DirectionDown)
            scene.settle { scene.menuFocused("close") }
            val beforeMove = scene.text("Rename").boundsInWindow.top
            sourceOffset = 40
            scene.settle { scene.text("Rename").boundsInWindow.top > beforeMove + 30 }
            assertTrue(scene.menuFocused("close"))
            created.complete(Instant.parse("2026-09-08T01:02:03Z"))
            scene.settle { scene.nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text.startsWith("Created at: ") } == true } }
            assertTrue(scene.menuFocused("close"))
            assertFalse(scene.nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text.startsWith("Updated at: ") } == true })
            scene.key(Key.Enter)
            scene.settle { calls.singleOrNull() === first && opening == null }
            assertSame(session, calls.single().target)
            scene.contextDescription(tabDescription)
            scene.settle { opening != null && scene.menuFocused("rename") }
            assertNotSame(first, opening)
            assertEquals(2, reads)
            sourceMounted = false
            scene.settle { opening == null && !scene.hasText("Rename") }
            assertTrue(failures.isEmpty())
            scene.frame()
        } finally { scene.close() }
        assertEquals(0, session.closeCount + app.closeCount)
    } }
    test("actual History origin delivers captured edit boundary and invalidates when its row unmounts") { runtimeScene { _, runtime, owner ->
        val agent = pageAgent("menu", runtime, owner)
        val session = NavigationSession(8, "History owner", agent)
        var opening by mutableStateOf<ApplicationHistoryMenuOpening?>(null)
        var sourceMounted by mutableStateOf(true)
        var customMenus by mutableStateOf(false)
        val edits = mutableListOf<Pair<ApplicationHistoryMenuOpening, String>>()
        val scene = OffscreenScene.create {
            val historyState = remember(agent.history) { ComposeHistoryViewState() }
            val graphical = rememberComposeAgentPageRenderer(historyState, onOpenHistoryContextMenu =
                if (customMenus) { { generation, index, item, placement ->
                    opening = ApplicationHistoryMenuOpening(session, agent, generation, index, item, placement)
                } } else null,
            )
            val renderer = object : AgentPageRenderer by graphical {
                @Composable override fun Frame(inputs: AgentPageMeasureInputs, content: @Composable (AgentPageRenderScope) -> Unit) {
                    graphical.Frame(inputs) { slots ->
                        content(object : AgentPageRenderScope by slots {
                            @Composable override fun Composer(viewModel: ComposerViewModel, newLineKey: NewLineKey, enabled: Boolean) {
                                UiText("Composer slot")
                            }
                        })
                    }
                }
            }
            if (sourceMounted) AgentPageContent(agent, NewLineKey.ShiftEnter, renderer, {}, {}, {})
            ApplicationHistoryMenuContent(
                opening, session, agent, rememberComposeApplicationMenuRenderer(),
                onDismiss = { if (opening === it) opening = null },
                onRevert = { error("unexpected revert") }, onFork = { error("unexpected fork") },
                onRevertAndEdit = { captured, text -> if (opening === captured) { edits += captured to text; opening = null } },
            )
        }
        try {
            scene.settle { scene.hasText("History menu") && scene.hasDescription("History entry 1") }
            val baseText = scene.nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
                .map { it.text }.toSet()
            scene.contextText("History menu")
            scene.settle { scene.semanticsOwnerCount() == 2 }
            // Discover the default action in this host's locale before installing the application menu.
            val selectionAction = scene.nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
                .map { it.text }.filterNot { it in baseText }.distinct().single()
            assertTrue(selectionAction.isNotBlank())
            scene.click("Composer slot")
            scene.settle { scene.semanticsOwnerCount() == 1 }
            customMenus = true
            scene.settle { true }
            scene.contextText("History menu")
            scene.settle { scene.hasText("Revert and edit") }
            assertFalse(scene.hasText(selectionAction), "The History menu must own a secondary press on selectable text.")
            assertEquals(2, scene.semanticsOwnerCount(), "Only the scene and its History menu should be present.")
            val first = assertNotNull(opening)
            assertSame(agent, first.agent)
            assertSame(session, first.session)
            assertEquals(1, first.storageIndex)
            assertEquals(agent.history.historyItems.value.generation, first.generation)
            scene.click("Revert and edit")
            scene.settle { edits.size == 1 && opening == null }
            assertSame(first, edits.single().first)
            assertEquals("History menu", edits.single().second)
            assertEquals("retained menu", agent.composer.state.value.text) // intent only; no alternate command implementation
            scene.contextDescription("History entry 1")
            scene.settle { opening != null && scene.hasText("Fork from here") }
            sourceMounted = false
            scene.settle { opening == null && !scene.hasText("Fork from here") }
            customMenus = false
            sourceMounted = true
            scene.settle { scene.hasText("History menu") }
            scene.contextText("History menu")
            scene.settle { scene.hasText(selectionAction) }
            assertNull(opening, "A standalone renderer must retain its text-selection context menu.")
            assertFalse(scene.hasText("Fork from here"))
            scene.frame()
        } finally {
            try { scene.close(); assertEquals(0, agent.historyCloses + agent.closeCount + session.closeCount) }
            finally { agent.closeFixture() }
        }
    } }
    test("revert confirmation keeps exact request id and cancels stale or unmounted requests") { runtimeScene { _, runtime, owner ->
        val actual = pageAgent("confirmation", runtime, owner)
        val action = MutableStateFlow<AgentHistoryActionState>(AgentHistoryActionState.None)
        val dismissed = mutableListOf<Long>()
        val confirmed = mutableListOf<Long>()
        val agent = object : AgentViewModel by actual {
            override val historyAction = action
            override fun dismissHistoryRevert(requestId: Long) { dismissed += requestId }
            override fun confirmHistoryRevert(requestId: Long) { confirmed += requestId }
        }
        var mounted by mutableStateOf(true)
        val scene = OffscreenScene.create {
            if (mounted) AgentHistoryRevertContent(agent, rememberComposeApplicationMenuRenderer())
        }
        try {
            scene.settle { actual.history.historyItems.value.size > 0 }
            val generation = actual.history.historyItems.value.generation
            action.value = AgentHistoryActionState.ConfirmRevert(11, 2, generation)
            scene.settle { scene.hasText("Revert history to here?") }
            scene.click("Revert")
            scene.settle { confirmed == listOf(11L) }
            action.value = AgentHistoryActionState.ConfirmRevert(12, 2, generation)
            scene.settle { dismissed.contains(11) }
            scene.click("Cancel")
            scene.settle { dismissed.contains(12) }
            action.value = AgentHistoryActionState.ConfirmRevert(13, 2, generation + 1)
            scene.settle { dismissed.contains(13) && !scene.hasText("Revert history to here?") }
            action.value = AgentHistoryActionState.ConfirmRevert(14, 2, generation)
            scene.settle { scene.hasText("Revert history to here?") }
            mounted = false
            scene.settle { dismissed.contains(14) && !scene.hasText("Revert history to here?") }
            assertEquals(listOf(11L), confirmed)
            scene.frame()
        } finally {
            try { scene.close(); assertEquals(0, actual.closeCount + actual.historyCloses) }
            finally { actual.closeFixture() }
        }
    } }
}

private fun OffscreenScene.menuFocused(key: String): Boolean = nodes().any {
    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Application menu action: $key") == true &&
        it.config.getOrNull(SemanticsProperties.Focused) == true
}
