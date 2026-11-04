package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.agent.contract.*
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemState
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemViewModel
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.app.application.contract.ApplicationPopupState
import io.github.stream29.kodex.app.application.contract.ApplicationTabSelection
import io.github.stream29.kodex.app.ui.UiText
import io.github.stream29.kodex.app.ui.compose.*
import io.github.stream29.kodex.cli.agent.*
import io.github.stream29.kodex.cli.app.*
import io.github.stream29.kodex.cli.settings.NewLineKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*
import kotlin.time.Instant
import kotlin.time.Duration.Companion.milliseconds

val offscreenApplicationMenusTest by testSuite {
    test("read-only assistant menu exposes display only and never admits history edits") { runtimeScene { _, runtime, owner ->
        val agent = pageAgent("markdown-menu", runtime, owner)
        agent.running.value = true
        val session = NavigationSession(91, "markdown", agent)
        var timestampReads = 0
        val message = object : MessageHistoryItemViewModel {
            override val index = 1
            override val state = MutableStateFlow<MessageHistoryItemState>(MessageHistoryItemState.Ready(
                StableAssistantMessage(listOf(ContentItem.OutputText("# Test"))), 0.milliseconds, null,
            ))
            override suspend fun readTimestamp(): Instant? { timestampReads++; return null }
        }
        var toggles = 0
        val placement = object : ApplicationHistoryMenuPlacement {}
        var opening by mutableStateOf<ApplicationHistoryMenuOpening?>(null)
        var observed = emptyList<ApplicationMenuEntry>()
        val scene = OffscreenScene.create {
            val graphical = rememberComposeApplicationMenuRenderer()
            val renderer = object : ApplicationMenuRenderer by graphical {
                override fun isHistoryPlaced(placement: ApplicationHistoryMenuPlacement) = true
                @Composable override fun History(
                    placement: ApplicationHistoryMenuPlacement, entries: List<ApplicationMenuEntry>, onDismiss: () -> Unit,
                ) { observed = entries }
            }
            ApplicationHistoryMenuContent(opening, session, agent, renderer,
                onDismiss = { if (opening === it) opening = null },
                onRevert = { error("running history must not revert") },
                onRevertAndEdit = { _, _ -> error("running history must not edit") },
                onFork = { error("running history must not fork") },
            )
        }
        try {
            scene.settle { agent.history.historyItems.value.size > 0 }
            opening = ApplicationHistoryMenuOpening(session, agent, agent.history.historyItems.value.generation,
                1, message, placement, ApplicationHistoryDisplayOption("Show raw") { toggles++ })
            scene.settle { observed.isNotEmpty() }
            val actions = observed.filterIsInstance<ApplicationMenuEntry.Action>()
            assertEquals(listOf("history-display-mode"), actions.map { it.key })
            actions.single().onClick()
            assertEquals(1, toggles)
            assertEquals(0, timestampReads)
            agent.running.value = false
            scene.settle { observed.filterIsInstance<ApplicationMenuEntry.Action>().any { it.key == "revert-to-here" } }
            assertTrue(observed.filterIsInstance<ApplicationMenuEntry.Action>().any { it.key == "history-display-mode" })
        } finally { scene.close(); agent.closeFixture() }
    } }
    test("shared command consumption rechecks current VM before consuming a retained History opening") { runtimeScene { _, runtime, owner ->
        val source = pageAgent("admission", runtime, owner)
        val agent = object : AgentViewModel by source {
            override val historyAction = MutableStateFlow<AgentHistoryActionState>(AgentHistoryActionState.None)
        }
        val session = NavigationSession(9, "Admission owner", agent)
        val app = object : NavigationApplication(listOf(session)) {
            override val popup = MutableStateFlow<ApplicationPopupState>(ApplicationPopupState.Closed)
        }
        app.selectTab(session)
        var opening by mutableStateOf<ApplicationHistoryMenuOpening?>(null)
        var revert: (() -> Unit)? = null
        var consumed = 0
        var placed = true
        val failures = mutableListOf<Throwable>()
        val scene = OffscreenScene.create {
            val navigation = app.navigation.collectAsState().value
            val graphical = rememberComposeApplicationMenuRenderer()
            val menu = object : ApplicationMenuRenderer by graphical {
                override fun isHistoryPlaced(placement: ApplicationHistoryMenuPlacement): Boolean =
                    placed && graphical.isHistoryPlaced(placement)
                @Composable override fun History(
                    placement: ApplicationHistoryMenuPlacement, entries: List<ApplicationMenuEntry>, onDismiss: () -> Unit,
                ) {
                    revert = entries.filterIsInstance<ApplicationMenuEntry.Action>().single { it.key == "revert-to-here" }.onClick
                    graphical.History(placement, entries, onDismiss)
                }
            }
            val historyState = remember { ComposeHistoryViewState() }
            val graphicalPage = rememberComposeAgentPageRenderer(historyState, onOpenHistoryContextMenu = { generation, index, item, placement ->
                opening = ApplicationHistoryMenuOpening(session, agent, generation, index, item, placement)
            })
            val page = object : AgentPageRenderer by graphicalPage {
                @Composable override fun Frame(inputs: AgentPageMeasureInputs, content: @Composable (AgentPageRenderScope) -> Unit) {
                    graphicalPage.Frame(inputs) { slots ->
                        content(object : AgentPageRenderScope by slots {
                            @Composable override fun Composer(viewModel: ComposerViewModel, newLineKey: NewLineKey, enabled: Boolean) {
                                UiText("Composer slot")
                            }
                        })
                    }
                }
            }
            AgentPageContent(agent, NewLineKey.ShiftEnter, page, {}, {}, {})
            ApplicationMenusContent(app, navigation, agent, null, opening, menu,
                admitTab = { error("No tab opening") },
                admitHistory = { expected ->
                    if (opening === expected) { consumed++; opening = null; true } else false
                }, focusEditedComposer = {}, onOperationFailure = failures::add,
            )
        }
        try {
            scene.settle { scene.hasDescription("History entry 1") }
            scene.contextDescription("History entry 1")
            scene.settle { revert != null && scene.hasText("Revert to here") }
            val retained = assertNotNull(revert)
            placed = false
            retained()
            assertEquals(0, consumed, "An unplaced anchor must be checked without awaiting dismissal/recomposition.")
            placed = true
            source.running.value = true
            retained()
            assertEquals(0, consumed, "Running changes must be checked without awaiting recomposition.")
            source.running.value = false
            app.navigation.value = app.navigation.value.copy(selection = ApplicationTabSelection.NewSession)
            retained()
            assertEquals(0, consumed, "A retained opening must not act on an unselected owner.")
            assertEquals("retained admission", agent.composer.state.value.text)
            assertTrue(failures.isEmpty())
        } finally { scene.close(); source.closeFixture() }
    } }

    test("retained tab command rejects an unplaced origin before host dismissal") { offscreenTest {
        val session = NavigationSession(19, "Placed owner", object : SidebarTestAgent() {
            override val running = MutableStateFlow(false)
        })
        val app = NavigationApplication(listOf(session))
        app.selectTab(session)
        var opening by mutableStateOf<ApplicationTabMenuTarget?>(null)
        var retained: (() -> Unit)? = null
        var placed = true
        var consumed = 0
        var description = ""
        val failures = mutableListOf<Throwable>()
        val scene = OffscreenScene.create {
            val navigation = app.navigation.collectAsState().value
            val projection = collectApplicationTabBar(navigation)
            description = "Session tab: ${projection.state.tabs.single().identity.value}"
            ApplicationTabBarContent(app, navigation, projection, rememberComposeSessionTabBarRenderer(),
                failures::add, { opening = it }, {})
            val graphical = rememberComposeApplicationMenuRenderer()
            val menu = object : ApplicationMenuRenderer by graphical {
                override fun isTabPlaced(placement: io.github.stream29.kodex.app.sessiontabbar.contract.SessionTabMenuPlacement): Boolean =
                    placed && graphical.isTabPlaced(placement)
                @Composable override fun Tab(
                    placement: io.github.stream29.kodex.app.sessiontabbar.contract.SessionTabMenuPlacement,
                    entries: List<ApplicationMenuEntry>, onDismiss: () -> Unit,
                ) {
                    retained = entries.filterIsInstance<ApplicationMenuEntry.Action>().single { it.key == "rename" }.onClick
                    graphical.Tab(placement, entries, onDismiss)
                }
            }
            ApplicationMenusContent(app, navigation, null, opening, null, menu,
                admitTab = { expected -> if (opening === expected) { consumed++; opening = null; true } else false },
                admitHistory = { error("No history menu") }, focusEditedComposer = {}, onOperationFailure = failures::add)
        }
        try {
            scene.settle { description.isNotEmpty() && scene.hasDescription(description) }
            scene.contextDescription(description)
            scene.settle { retained != null && scene.hasText("Rename") }
            placed = false
            assertNotNull(retained).invoke()
            assertEquals(0, consumed)
            assertTrue(failures.isEmpty())
            assertNotNull(opening)
        } finally { scene.close() }
        assertEquals(0, session.closeCount)
    } }

    test("real tab origin retains keyboard focus across optional metadata and closes on source unmount") { offscreenTest {
        val fullName = "Full keyboard-accessible session name " + "完整名称 ".repeat(20)
        val created = CompletableDeferred<Instant>()
        var reads = 0
        val session = object : NavigationSession(42, fullName, object : SidebarTestAgent() {
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
            DropdownMenuItem({ Text("Baseline menu action") }, {},
                modifier = Modifier.semantics { contentDescription = "Baseline menu action" })
        }
        try {
            scene.settle { scene.hasText(fullName) }
            scene.contextDescription(tabDescription)
            scene.settle { scene.hasText("Index: 42") && scene.menuFocused("rename") }
            assertEquals(2, scene.nodes().count {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == fullName } == true
            }, "The existing keyboard menu exposes the complete untruncated name without hover.")
            val baselineHeight = scene.nodes().first {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Baseline menu action") == true
            }.boundsInRoot.height
            val actions = scene.nodes().filter {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { text -> text.startsWith("Application menu action: ") } == true
            }
            assertEquals(4, actions.size)
            assertTrue(actions.all { it.boundsInRoot.height < baselineHeight })
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
            scene.clickDescription(tabDescription)
            scene.key(Key.F10, shift = true)
            scene.settle { opening != null && scene.menuFocused("rename") }
            assertNotSame(first, opening)
            assertEquals(2, reads)
            scene.escape()
            scene.settle {
                opening == null && scene.nodes().any {
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(tabDescription) == true &&
                        it.config.getOrNull(SemanticsProperties.Focused) == true
                }
            }
            scene.key(Key.Menu)
            scene.settle { opening != null && scene.menuFocused("rename") }
            assertEquals(3, reads)
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
            scene.settle { scene.overlayTextFocused("Cancel") }
            scene.key(Key.Tab)
            scene.settle { scene.overlayTextFocused("Revert") }
            scene.key(Key.Enter)
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
