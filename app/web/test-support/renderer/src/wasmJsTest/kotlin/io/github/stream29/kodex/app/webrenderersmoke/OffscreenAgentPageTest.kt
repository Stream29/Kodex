package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.app.agent.contract.*
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationViewModel
import io.github.stream29.kodex.app.ui.UiText
import io.github.stream29.kodex.app.ui.compose.*
import io.github.stream29.kodex.cli.agent.*
import io.github.stream29.kodex.cli.app.*
import io.github.stream29.kodex.cli.history.AgentHistorySource
import io.github.stream29.kodex.cli.history.createAgentHistoryViewModel
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.rpc.models.AgentStateValue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.files.Path
import kotlin.test.*
import kotlin.time.Instant

val offscreenAgentPageTest by testSuite {
    test("status action preserves keyboard focus and rejects stale disabled callback") { offscreenTest {
        val enabled = mutableStateOf(true)
        var calls = 0
        val scene = OffscreenScene.create(width = 320, height = 100) {
            rememberComposeAgentStatusRenderer().Action("Stop", enabled.value) { calls++ }
        }
        fun descendants(node: SemanticsNode): List<SemanticsNode> =
            listOf(node) + node.children.flatMap(::descendants)
        fun stop(): SemanticsNode = scene.nodes().first { node ->
            node.config.getOrNull(SemanticsProperties.Role) == Role.Button &&
                descendants(node).any { child ->
                    child.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Stop" } == true
                }
        }
        try {
            scene.settle { scene.hasText("Stop") }
            assertTrue(stop().boundsInWindow.width >= 32f && stop().boundsInWindow.height >= 32f)
            val retained = assertNotNull(stop().config.getOrNull(SemanticsActions.OnClick)?.action)
            val unfocused = scene.frame()
            assertTrue(assertNotNull(stop().config.getOrNull(SemanticsActions.RequestFocus)?.action)())
            scene.settle { stop().config.getOrNull(SemanticsProperties.Focused) == true }
            assertFalse(unfocused.contentEquals(scene.frame()), "Keyboard focus must be visible on Stop.")
            scene.key(Key.Enter)
            scene.settle { calls == 1 }
            enabled.value = false
            scene.settle { SemanticsProperties.Disabled in stop().config }
            retained()
            assertEquals(1, calls)
        } finally { scene.close() }
    } }
    for (height in listOf(160, 240)) {
      for (scale in listOf(1f, 1.5f)) {
        test("short Agent page can reveal Settings after all auxiliary content height=$height font=$scale") { runtimeScene { _, runtime, owner ->
            val agent = pageAgent("short", runtime, owner)
            agent.inputPorts.pending.value = inputEvent("short-input")
            agent.suggestionPorts.pending.value = suggestionEvent("short-suggestion")
            agent.pendingSteer.value = List(6) { StableUserMessage(listOf(ContentItem.InputText("Queued $it"))) }
            agent.notification.value = AgentNotification(21, AgentNotificationLevel.Information, "Saved ".repeat(60))
            val scene = OffscreenScene.create(width = 320, height = height) {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                    ProbedAgentPage(agent, AgentPageProbe())
                }
            }
            try {
                scene.settle { scene.hasText("Settings") }
                val settings = scene.nodes().single { node ->
                    node.config.getOrNull(SemanticsActions.RequestFocus) != null &&
                        node.children.any { it.config.getOrNull(SemanticsProperties.Text)?.any {
                            it.text == "Settings"
                        } == true }
                }
                assertTrue(settings.config[SemanticsActions.RequestFocus].action!!.invoke())
                scene.settle { scene.text("Settings").boundsInWindow.height > 0 }
                val bounds = scene.text("Settings").boundsInWindow
                assertTrue(bounds.top >= 0 && bounds.bottom <= height, "$bounds in height=$height")
                assertEquals("retained short", agent.composer.state.value.text)
                assertPageBorrowed(agent)
            } finally {
                try { scene.close(); assertPageBorrowed(agent) } finally { agent.closeFixture() }
            }
        } }
      }
    }
    test("ordinary Agent page fills remaining finite history without unnecessary page scroll") { runtimeScene { _, runtime, owner ->
        val agent = pageAgent("normal", runtime, owner)
        val scene = OffscreenScene.create(width = 800, height = 900) { ProbedAgentPage(agent, AgentPageProbe()) }
        try {
            scene.settle { scene.hasText("History normal") && scene.hasDescription("Agent page history") }
            assertTrue(scene.nodes().single {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Agent page history") == true
            }.boundsInWindow.height > 500)
            val historyBounds = scene.nodes().single {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Agent page history") == true
            }.boundsInWindow
            val entryBounds = scene.nodes().single {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("History entry 1") == true
            }.boundsInWindow
            assertEquals(historyBounds.bottom, entryBounds.bottom, 1f,
                "Sparse History must touch the allocated history bottom, not the page top")
            val outer = scene.nodes().single {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Agent page") == true &&
                    it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
            }
            assertEquals(0f, outer.config[SemanticsProperties.VerticalScrollAxisRange].maxValue())
            assertTrue(scene.text("Settings").boundsInWindow.bottom <= 900)
        } finally {
            try { scene.close(); assertPageBorrowed(agent) } finally { agent.closeFixture() }
        }
    } }
    test("shared page observes one information outlet and one pending preview with real product History") { runtimeScene { _, runtime, owner ->
        val agent = pageAgent("first", runtime, owner)
        val probe = AgentPageProbe()
        val scene = OffscreenScene.create { ProbedAgentPage(agent, probe) }
        try {
            scene.settle { scene.hasText("History first") && scene.hasText("Composer enabled=true") }
            assertSame(agent.history, probe.history)
            assertSame(agent.composer, probe.composer)
            assertTrue(probe.canEdit)
            agent.notification.value = AgentNotification(1, AgentNotificationLevel.Information, "Saved", "one detail")
            agent.pendingSteer.value = listOf(StableUserMessage(listOf(ContentItem.InputText("Queued once"))))
            scene.settle { scene.hasText("Saved one detail") && scene.hasText("  ↳ Queued once") }
            assertEquals(1, probe.calls.count { it == "pending" })
            assertEquals(listOf("history", "pending", "notice", "composer"), probe.calls)
            assertTrue(scene.text("Saved one detail").boundsInWindow.top < scene.text("Composer enabled=true").boundsInWindow.top)
            for (level in listOf(AgentNotificationLevel.Warning, AgentNotificationLevel.Error)) {
                agent.notification.value = AgentNotification(2, level, "Only the application error outlet")
                scene.settle { !scene.hasText("Saved one detail") }
                assertFalse(scene.hasText("Only the application error outlet"))
            }
            agent.running.value = true
            scene.settle { !probe.canEdit && scene.hasText("Stop") }
            agent.running.value = false
            agent.state.value = AgentStateValue.ExternalWrite
            scene.settle { !probe.canEdit && scene.hasText("Resume") }
            agent.state.value = AgentStateValue.ToolCompleted
            scene.settle { probe.canEdit }
            scene.frame()
        } finally {
            try { scene.close(); assertPageBorrowed(agent) } finally { agent.closeFixture() }
        }
    } }
    test("shared pending branches borrow exact children and disable only ordinary Composer focus and editing") { runtimeScene { _, runtime, owner ->
        val agent = pageAgent("pending", runtime, owner)
        val probe = AgentPageProbe()
        val browsed = mutableListOf<String>()
        val scene = OffscreenScene.create { ProbedAgentPage(agent, probe, browsed::add) }
        try {
            scene.settle { probe.enabled == true }
            agent.inputPorts.pending.value = inputEvent("input-page")
            scene.settle { probe.input != null && probe.enabled == false && scene.hasText("Scope: Which scope?") }
            assertSame(agent.requestUserInput, probe.input)
            assertSame<RequestUserInputState?>(agent.requestUserInput.state.value, probe.inputState)
            agent.suggestionPorts.pending.value = suggestionEvent("suggest-page")
            scene.settle { probe.suggestion != null && scene.hasText("First task") }
            assertSame(agent.suggestSubagentTask, probe.suggestion)
            assertSame<SuggestSubagentTaskState?>(agent.suggestSubagentTask.state.value, probe.suggestionState)
            assertEquals(listOf("history", "input", "suggestion", "composer"), probe.calls)
            assertEquals(2, probe.pendingPanels)
            assertNotNull(probe.browseSuggestion).invoke("suggest-page")
            assertEquals(listOf("suggest-page"), browsed)
            agent.inputPorts.pending.value = null
            scene.settle { probe.input == null && probe.enabled == false }
            assertEquals(1, probe.pendingPanels)
            agent.suggestionPorts.pending.value = null
            scene.settle { probe.suggestion == null && probe.enabled == true }
            assertEquals(0, probe.pendingPanels)
            assertEquals("retained pending", agent.composer.state.value.text)
            scene.frame()
        } finally {
            try { scene.close(); assertPageBorrowed(agent) } finally { agent.closeFixture() }
        }
    } }
    test("replacing and temporarily unmounting a page neither redirects nor closes borrowed children") { runtimeScene { _, runtime, owner ->
        val first = pageAgent("old", runtime, owner)
        val second = pageAgent("new", runtime, owner)
        var selected by mutableStateOf(first)
        var mounted by mutableStateOf(true)
        val probe = AgentPageProbe()
        val scene = OffscreenScene.create { if (mounted) ProbedAgentPage(selected, probe) else UiText("Page unmounted") }
        try {
            scene.settle { scene.hasText("History old") }
            selected = second
            scene.settle { scene.hasText("History new") && probe.composer === second.composer }
            assertFalse(scene.hasText("History old"))
            assertPageBorrowed(first)
            mounted = false
            scene.settle { scene.hasText("Page unmounted") }
            first.composer.update("background old")
            mounted = true
            scene.settle { scene.hasText("History new") }
            assertSame(second.history, probe.history)
            assertEquals("retained new", second.composer.state.value.text)
            assertEquals("background old", first.composer.state.value.text)
            scene.frame()
        } finally {
            try { scene.close(); assertPageBorrowed(first); assertPageBorrowed(second) }
            finally { first.closeFixture(); second.closeFixture() }
        }
    } }
}

/** DOM Composer is probed as a slot here; its actual root mount/focus remains a host integration check. */
@Composable
private fun ProbedAgentPage(agent: PageTestAgent, probe: AgentPageProbe, onBrowseSuggestion: (String) -> Unit = {}) {
    val retained = remember(agent.history) { ComposeHistoryViewState() }
    val graphical = rememberComposeAgentPageRenderer(retained)
    val renderer = object : AgentPageRenderer by graphical {
        @Composable
        override fun Frame(inputs: AgentPageMeasureInputs, content: @Composable (AgentPageRenderScope) -> Unit) {
            probe.calls.clear()
            probe.input = null
            probe.suggestion = null
            probe.pendingPanels = inputs.pendingToolPanelCount
            graphical.Frame(inputs) { slots ->
                content(object : AgentPageRenderScope by slots {
                    @Composable
                    override fun History(viewModel: AgentHistoryViewModel, shellSessions: AgentShellSessionRegistry, canEdit: Boolean) {
                        probe.calls += "history"; probe.history = viewModel; probe.canEdit = canEdit
                        slots.History(viewModel, shellSessions, canEdit)
                    }
                    @Composable
                    override fun UserInput(viewModel: RequestUserInputViewModel, state: RequestUserInputState.Pending) {
                        probe.calls += "input"; probe.input = viewModel; probe.inputState = state
                        slots.UserInput(viewModel, state)
                    }
                    @Composable
                    override fun Suggestion(viewModel: SuggestSubagentTaskViewModel, state: SuggestSubagentTaskState.Pending, onBrowseWorkingDirectory: (String) -> Unit) {
                        probe.calls += "suggestion"; probe.suggestion = viewModel; probe.suggestionState = state
                        probe.browseSuggestion = onBrowseWorkingDirectory
                        slots.Suggestion(viewModel, state, onBrowseWorkingDirectory)
                    }
                    @Composable
                    override fun PendingSteer() { probe.calls += "pending"; slots.PendingSteer() }
                    @Composable
                    override fun Notice(message: String) { probe.calls += "notice"; slots.Notice(message) }
                    @Composable
                    override fun Composer(viewModel: ComposerViewModel, newLineKey: NewLineKey, enabled: Boolean) {
                        probe.calls += "composer"; probe.composer = viewModel; probe.enabled = enabled
                        UiText("Composer enabled=$enabled")
                    }
                })
            }
        }
    }
    AgentPageContent(agent, NewLineKey.ShiftEnter, renderer, {}, onBrowseSuggestion, {})
}

private class AgentPageProbe {
    var pendingPanels = 0
    val calls = mutableListOf<String>()
    var history: AgentHistoryViewModel? = null
    var composer: ComposerViewModel? = null
    var input: RequestUserInputViewModel? = null
    var inputState: RequestUserInputState.Pending? = null
    var suggestion: SuggestSubagentTaskViewModel? = null
    var suggestionState: SuggestSubagentTaskState.Pending? = null
    var browseSuggestion: ((String) -> Unit)? = null
    var enabled: Boolean? = null
    var canEdit = false
}

internal suspend fun pageAgent(name: String, runtime: RuntimeConfigurationViewModel, owner: CoroutineScope): PageTestAgent {
    val stored = InMemoryKodexAgentStorage.empty()
    stored.index[1] = StableUserMessage(listOf(ContentItem.InputText("History $name")))
    stored.timestamp[1] = Instant.fromEpochMilliseconds(1_000)
    val historyScope = CoroutineScope(owner.coroutineContext + Job(owner.coroutineContext[Job]))
    val history = createAgentHistoryViewModel(
        AgentHistorySource(stored, MutableStateFlow(1), MutableStateFlow(KodexAgentStateValue.UserMessage)),
        historyScope, MutableStateFlow(false),
    )
    return PageTestAgent(name, runtime, history, owner)
}

internal class PageTestAgent(
    name: String,
    override val runtimeConfiguration: RuntimeConfigurationViewModel,
    historyOwner: AgentHistoryViewModel,
    owner: CoroutineScope,
) : SidebarTestAgent() {
    override val state = MutableStateFlow<AgentStateValue>(AgentStateValue.AssistantMessage)
    override val running = MutableStateFlow(false)
    override val pendingSteer = MutableStateFlow<List<StableIndexEvent.Steerable>>(emptyList())
    override val tokenCount = MutableStateFlow<Long?>(null)
    override val settings = MutableStateFlow(KodexAgentSettings(OpenAiModelId("test-model"), cwd = Path("/opaque/$name")))
    override val notification = MutableStateFlow<AgentNotification?>(null)
    val inputPorts = InputPorts().also { it.pending.value = null }
    val suggestionPorts = SuggestionPorts().also { it.pending.value = null }
    var inputCloses = 0
    var suggestionCloses = 0
    var historyCloses = 0
    override val history = object : AgentHistoryViewModel by historyOwner {
        override fun close() { historyCloses++; historyOwner.close() }
    }
    private val inputOwner = createRequestUserInputViewModel(inputPorts, owner)
    private val suggestionOwner = createSuggestSubagentTaskViewModel(suggestionPorts, owner)
    override val requestUserInput = object : RequestUserInputViewModel by inputOwner {
        override fun close() { inputCloses++; inputOwner.close() }
    }
    override val suggestSubagentTask = object : SuggestSubagentTaskViewModel by suggestionOwner {
        override fun close() { suggestionCloses++; suggestionOwner.close() }
    }
    override val composer = createComposerViewModel(ComposerOwnerId(name), object : ComposerDependencies {
        override val runtime = object : ComposerRuntimePort {
            override val running = this@PageTestAgent.running
            override val pendingSteer = this@PageTestAgent.pendingSteer
        }
        override val requestInput = object : ComposerRequestInputPort {
            override val presentation = MutableStateFlow<ComposerRequestInputPresentation>(ComposerRequestInputPresentation.None)
        }
        override val submit = ComposerSubmitPort { _, _ -> error("No DOM submit in page wiring test") }
        override val steer = ComposerSteerPort { _, _ -> error("No DOM steer in page wiring test") }
        override val cancellation = ComposerCancellationPort { error("No cancellation in page wiring test") }
        override val resume = ComposerResumePort { error("No resume in page wiring test") }
        override val failures = ComposerFailureReporter { _, _ -> error("No failure in page wiring test") }
    }, owner).also { it.update("retained $name") }

    fun closeFixture() {
        composer.close(); requestUserInput.close(); suggestSubagentTask.close(); history.close()
    }
}

private fun assertPageBorrowed(agent: PageTestAgent) {
    assertEquals(0, agent.closeCount)
    assertEquals(ComposerLifecycle.Open, agent.composer.state.value.lifecycle)
    assertFalse(agent.runtimeConfiguration.state.value.closed)
    assertEquals(0, agent.inputCloses + agent.suggestionCloses + agent.historyCloses)
}
