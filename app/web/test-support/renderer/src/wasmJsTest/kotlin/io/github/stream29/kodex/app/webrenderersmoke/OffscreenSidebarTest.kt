@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.app.agent.contract.*
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationViewModel
import io.github.stream29.kodex.app.ui.compose.rememberComposeSessionSidebarRenderer
import io.github.stream29.kodex.cli.app.*
import io.github.stream29.kodex.cli.historyindex.HistoryIndexMenuContent
import io.github.stream29.kodex.cli.historyindex.HistoryIndexMenuOpening
import io.github.stream29.kodex.cli.settings.SidebarContent
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.rpc.models.AgentStateValue
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.Path
import kotlin.test.*

val offscreenSidebarTest by testSuite {
    test("right sidebar choice uses keyboard focus and emits only its exact side") { offscreenTest {
        val choices = mutableListOf<Pair<SessionSidebarSide, SidebarContent>>()
        val scene = OffscreenScene.create(width = 360, height = 240) {
            rememberComposeSessionSidebarRenderer(onSelectContent = { side, choice -> choices += side to choice })
                .Header(SessionSidebarSide.Right, SidebarContent.None, "None") {}
        }
        fun descendants(node: SemanticsNode): List<SemanticsNode> =
            listOf(node) + node.children.flatMap(::descendants)
        fun option(label: String) = scene.nodes().first { node ->
            node.config.getOrNull(SemanticsActions.OnClick) != null &&
                node.config.getOrNull(SemanticsProperties.Selected) != null &&
                descendants(node).any { child ->
                    child.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
                }
        }
        try {
            scene.settle { scene.hasDescription("Sidebar content right") }
            scene.clickDescription("Sidebar content right")
            scene.settle { scene.hasText("Terminal sessions") }
            assertTrue(assertNotNull(option("None").config.getOrNull(SemanticsActions.RequestFocus)?.action)())
            scene.key(Key.DirectionDown)
            scene.settle { option("Terminal sessions").config.getOrNull(SemanticsProperties.Focused) == true }
            assertTrue(choices.isEmpty())
            scene.key(Key.Enter)
            scene.settle { choices.size == 1 && !scene.hasText("Terminal sessions") }
            assertEquals(listOf(SessionSidebarSide.Right to SidebarContent.TerminalSessions), choices)
        } finally { scene.close() }
    } }
    test("shell summary preserves a command's own leading status-like character") { offscreenTest {
        val shell = SidebarTestShell(23, "● user command\n  second line")
        val scene = OffscreenScene.create(width = 320, height = 120) {
            Box(Modifier.fillMaxWidth().height(80.dp)) {
                ShellSessionSidebarContent(SessionSidebarSide.Left, shell, rememberComposeSessionSidebarRenderer())
            }
        }
        try {
            scene.settle { scene.hasText("● user command   second line") }
            assertEquals("Running", scene.shellNodes(23).single().config.getOrNull(SemanticsProperties.StateDescription))
            assertFalse(scene.hasText("● ● user command   second line"))
        } finally { scene.close() }
    } }

    test("shell display has no fake click and keyboard and semantic menus expose the complete command") { offscreenTest {
        val command = "echo first\n  echo second\n" + "complete-command ".repeat(30)
        val shell = SidebarTestShell(19, command)
        var opening by mutableStateOf<SidebarShellMenuOpening?>(null)
        val scene = OffscreenScene.create(width = 320, height = 240) {
            val renderer = rememberComposeSessionSidebarRenderer(onOpenShellMenu = { opening = it })
            Box(Modifier.fillMaxWidth().height(80.dp)) {
                ShellSessionSidebarContent(SessionSidebarSide.Left, shell, renderer)
            }
            val current = opening
            SidebarShellMenuContent(current, renderer) { if (opening === current) opening = null }
        }
        try {
            scene.settle { scene.hasDescription("Open menu for shell session 19") }
            assertFalse(scene.hasDescription("Actions for shell session 19"))
            val row = scene.shellNodes(19).single()
            assertNull(row.config.getOrNull(SemanticsActions.OnClick))
            assertEquals("Running", row.config.getOrNull(SemanticsProperties.StateDescription))
            val summary = command.lineSequence().joinToString(" ")
            assertTrue(scene.hasText(summary))
            assertFalse(scene.hasText("● $summary"), "The status indicator must not be part of the command text.")
            assertTrue(row.boundsInRoot.height <= 32f)
            val unfocused = scene.frame()
            assertTrue(assertNotNull(row.config.getOrNull(SemanticsActions.RequestFocus)?.action).invoke())
            scene.settle { scene.shellNodes(19).single().config.getOrNull(SemanticsProperties.Focused) == true }
            assertFalse(unfocused.contentEquals(scene.frame()), "Keyboard focus must be visible on the row.")
            scene.key(Key.Menu)
            scene.settle { scene.hasText(command) && scene.hasText("Close session") }
            assertTrue(scene.text("Close session").boundsInWindow.bottom <= 240)
            scene.escape()
            scene.settle { opening == null && scene.nodes().any {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Shell session 19") == true &&
                    it.config.getOrNull(SemanticsProperties.Focused) == true
            } }
            assertEquals(0, shell.closeCount)
            val action = scene.nodes().single {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Open menu for shell session 19") == true
            }
            assertTrue(assertNotNull(action.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
            scene.settle { opening != null && scene.hasText(command) }
            scene.escape()
            scene.settle { opening == null }
            scene.key(Key.F10, shift = true)
            scene.settle { opening != null && scene.hasText(command) }
            scene.key(Key.Enter)
            scene.settle { shell.closeCount == 1 && opening == null }
            scene.frame()
        } finally { scene.close() }
    } }

    test("both shared bodies sort and filter exact Shell handles without closing the registry or Agent") { offscreenTest {
        val first = SidebarTestShell(1, "first")
        val second = SidebarTestShell(9, "second")
        val retained = SidebarTestShell(5, "retained").also { it.completed.value = true }
        val registry = MutableStateFlow<Map<Int, AgentShellSession>>(linkedMapOf(9 to second, 5 to retained, 1 to first))
        val agent = SidebarTestAgent(registry)
        val exited = mutableListOf<SidebarShellInteraction>()
        val scene = OffscreenScene.create {
            val renderer = rememberComposeSessionSidebarRenderer(onShellHoverChanged = { origin, entered -> if (!entered) exited += origin })
            Row(Modifier.fillMaxWidth().height(260.dp)) {
                Box(Modifier.width(340.dp)) { SessionSidebarContent(SessionSidebarSide.Left, SidebarContent.TerminalSessions, agent, renderer, {}) }
                Box(Modifier.width(340.dp)) { SessionSidebarContent(SessionSidebarSide.Right, SidebarContent.TerminalSessions, agent, renderer, {}) }
            }
        }
        try {
            scene.settle { scene.shellNodes(1).size == 2 && scene.shellNodes(9).size == 2 }
            assertTrue(scene.shellNodes(5).isEmpty())
            assertTrue(scene.shellNodes(1).first().boundsInWindow.top < scene.shellNodes(9).first().boundsInWindow.top)
            first.completed.value = true
            scene.settle { scene.shellNodes(1).isEmpty() && exited.count { it.session === first } == 2 }
            assertEquals(2, scene.shellNodes(9).size)
            assertEquals(3, registry.value.size)
            second.completed.value = true
            scene.settle { scene.shellNodes(9).isEmpty() && scene.hasText("No running terminal sessions") }
            assertEquals(0, first.closeCount + second.closeCount + retained.closeCount + agent.closeCount)
            scene.frame()
        } finally { scene.close() }
        assertEquals(0, first.closeCount + second.closeCount + retained.closeCount + agent.closeCount)
    } }

    test("invalidated Shell menu returns focus to its sidebar entry") { offscreenTest {
        val shell = SidebarTestShell(19, "echo focused")
        val agent = SidebarTestAgent(MutableStateFlow(mapOf(19 to shell)))
        var opening by mutableStateOf<SidebarShellMenuOpening?>(null)
        val scene = OffscreenScene.create {
            val renderer = rememberComposeSessionSidebarRenderer(onOpenShellMenu = { opening = it })
            Box(Modifier.width(320.dp).height(160.dp)) {
                SessionSidebarContent(SessionSidebarSide.Left, SidebarContent.TerminalSessions, agent, renderer, {})
            }
            val current = opening
            SidebarShellMenuContent(current, renderer) { if (opening === current) opening = null }
        }
        try {
            scene.settle { scene.hasDescription("Shell session 19") }
            val row = scene.shellNodes(19).single()
            assertTrue(assertNotNull(row.config.getOrNull(SemanticsActions.RequestFocus)?.action).invoke())
            scene.key(Key.Menu)
            scene.settle { scene.hasText("Close session") }
            shell.completed.value = true
            scene.settle { opening == null && !scene.hasDescription("Shell session 19") && scene.nodes().any {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Sidebar content left") == true &&
                    it.config.getOrNull(SemanticsProperties.Focused) == true
            } }
            assertEquals(0, shell.closeCount)
        } finally { scene.close() }
    } }

    test("invalidated Index menu returns focus to its sidebar entry") { offscreenTest {
        val index = IndexRenderFixture(listOf(1))
        val agent = SidebarTestAgent(MutableStateFlow(emptyMap()), index)
        var opening by mutableStateOf<HistoryIndexMenuOpening?>(null)
        val scene = OffscreenScene.create {
            val renderer = rememberComposeSessionSidebarRenderer(onOpenIndexMenu = { opening = it })
            Box(Modifier.width(320.dp).height(160.dp)) {
                SessionSidebarContent(SessionSidebarSide.Left, SidebarContent.HistoryIndex, agent, renderer, {})
            }
            val current = opening
            val indexRenderer = io.github.stream29.kodex.app.ui.compose.rememberComposeHistoryIndexRenderer()
            HistoryIndexMenuContent(current, indexRenderer) { if (opening === current) opening = null }
        }
        try {
            scene.settle { scene.hasDescription("History index 1") }
            val row = scene.nodes().single {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("History index 1") == true
            }
            assertTrue(assertNotNull(row.config.getOrNull(SemanticsActions.RequestFocus)?.action).invoke())
            scene.key(Key.Menu)
            scene.settle { scene.hasText("Check out") }
            index.window.value = index.window.value.copy(generation = 8, indexes = emptyList())
            scene.settle { opening == null && !scene.hasDescription("History index 1") && scene.nodes().any {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Sidebar content left") == true &&
                    it.config.getOrNull(SemanticsProperties.Focused) == true
            } }
            assertTrue(index.scrolled.isEmpty())
        } finally { scene.close(); index.close() }
    } }

    test("controlled content menu switches exact borrowed Index and None and emits collapse only") { offscreenTest {
        val shell = SidebarTestShell(1, "command")
        val index = IndexRenderFixture(listOf(3))
        val agent = SidebarTestAgent(MutableStateFlow(mapOf(1 to shell)), index)
        var content by mutableStateOf(SidebarContent.TerminalSessions)
        val choices = mutableListOf<Pair<SessionSidebarSide, SidebarContent>>()
        var collapses = 0
        val scene = OffscreenScene.create {
            val renderer = rememberComposeSessionSidebarRenderer(onSelectContent = { side, chosen ->
                choices += side to chosen
                content = chosen
            })
            Box(Modifier.width(340.dp).height(260.dp)) {
                SessionSidebarContent(SessionSidebarSide.Left, content, agent, renderer, { collapses++ })
            }
        }
        try {
            scene.settle { scene.hasDescription("Shell session 1") }
            scene.clickDescription("Sidebar content left")
            scene.settle { scene.hasText("History Index") }
            fun descendants(node: SemanticsNode): List<SemanticsNode> =
                listOf(node) + node.children.flatMap(::descendants)
            val option = scene.nodes().first { node ->
                node.config.getOrNull(SemanticsActions.OnClick) != null &&
                    descendants(node).any { child ->
                        child.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "History Index" } == true
                    }
            }
            assertTrue(option.boundsInWindow.height in 32f..40f, "Sidebar choice must use compact row geometry.")
            assertEquals(false, option.config.getOrNull(SemanticsProperties.Selected))
            scene.click("History Index")
            scene.settle { scene.hasText("Row 3") && scene.shellNodes(1).isEmpty() }
            assertEquals(listOf(SessionSidebarSide.Left to SidebarContent.HistoryIndex), choices)
            scene.clickDescription("Sidebar content left")
            scene.settle { scene.hasText("None") }
            scene.click("None")
            scene.settle { !scene.hasText("Row 3") }
            assertTrue(index.rows.all { it.state.value == HistoryIndexReadState.Closed })
            assertEquals(SessionSidebarSide.Left to SidebarContent.None, choices.last())
            scene.clickDescription("Collapse left sidebar")
            assertEquals(1, collapses)
            assertEquals(0, shell.closeCount + agent.closeCount)
            assertTrue(index.isActive)
            scene.frame()
        } finally { scene.close(); index.close() }
    } }

    test("Shell hover preserves complete multiline command and completion dismisses without Close") { offscreenTest {
        val command = "echo first\n  echo second\n" + "x".repeat(300)
        val shell = SidebarTestShell(42, command)
        var hovering by mutableStateOf<SidebarShellInteraction?>(null)
        val scene = OffscreenScene.create {
            val renderer = rememberComposeSessionSidebarRenderer(onShellHoverChanged = { origin, entered ->
                if (entered) hovering = origin else if (hovering === origin) hovering = null
            })
            Box(Modifier.width(320.dp).height(80.dp)) { ShellSessionSidebarContent(SessionSidebarSide.Left, shell, renderer) }
            val current = hovering
            SidebarShellHoverContent(current, renderer, {}, { if (hovering === current) hovering = null })
        }
        try {
            scene.settle { scene.hasDescription("Shell session 42") }
            scene.scene.sendPointerEvent(PointerEventType.Move, scene.shellNodes(42).single().boundsInWindow.center)
            scene.settle { scene.hasText(command) && scene.hasText("Session 42") }
            assertSame(shell, assertNotNull(hovering).session)
            scene.frame()
            shell.completed.value = true
            scene.settle { hovering == null && !scene.hasText(command) }
            assertEquals(0, shell.closeCount)
        } finally { scene.close() }
    } }

    test("menu closes its captured handle rather than an equal-id replacement and stale completion rejects Close") { offscreenTest {
        val captured = SidebarTestShell(7, "old")
        val replacement = SidebarTestShell(7, "new")
        val registry = MutableStateFlow<Map<Int, AgentShellSession>>(mapOf(7 to captured))
        var opening by mutableStateOf<SidebarShellMenuOpening?>(null)
        var close: (() -> Unit)? = null
        val scene = OffscreenScene.create {
            val graphical = rememberComposeSessionSidebarRenderer(onOpenShellMenu = { opening = it })
            val renderer = object : SessionSidebarRenderer by graphical {
                @Composable override fun ShellMenu(
                    opening: SidebarShellMenuOpening, label: String, onDismiss: () -> Unit, onClose: () -> Unit,
                ) { close = onClose; graphical.ShellMenu(opening, label, onDismiss, onClose) }
            }
            Box(Modifier.width(320.dp).height(80.dp)) { ShellSessionSidebarContent(SessionSidebarSide.Left, captured, renderer) }
            val current = opening
            SidebarShellMenuContent(current, renderer, { if (opening === current) opening = null })
        }
        try {
            scene.settle { scene.hasDescription("Shell session 7") }
            scene.secondaryShell(7)
            scene.settle { scene.hasText("Close session") }
            registry.value = mapOf(7 to replacement)
            assertSame(replacement, registry.value[7])
            scene.click("Close session")
            scene.settle { opening == null }
            assertEquals(1, captured.closeCount)
            assertEquals(0, replacement.closeCount)
            scene.secondaryShell(7)
            scene.settle { scene.hasText("Close session") }
            val staleClose = assertNotNull(close)
            captured.completed.value = true
            scene.settle { opening == null && !scene.hasText("Close session") }
            staleClose()
            assertEquals(1, captured.closeCount)
            assertEquals(0, replacement.closeCount)
            scene.frame()
        } finally { scene.close() }
    } }
}

internal class SidebarTestShell(override val sessionId: Int, command: String) : AgentShellSession {
    override val arguments = ExecCommandArguments(command, yieldTimeMillis = 10_000)
    override val completed = MutableStateFlow(false)
    var closeCount = 0
    override fun close() { closeCount++ }
}

/** Test-only borrowed owner; missing observations fail rather than fabricating product state. */
internal open class SidebarTestAgent(
    sessions: StateFlow<Map<Int, AgentShellSession>> = MutableStateFlow(emptyMap()),
    override val historyIndex: HistoryIndexViewModel = IndexRenderFixture(emptyList()),
) : AgentViewModel {
    override val shellSessions = object : AgentShellSessionRegistry { override val activeSessions = sessions }
    var closeCount = 0
    override fun close() { closeCount++ }
    override val storageUri: String get() = unused()
    override val composer: ComposerViewModel get() = unused()
    override val history: AgentHistoryViewModel get() = unused()
    override val runtimeConfiguration: RuntimeConfigurationViewModel get() = unused()
    override val requestUserInput: RequestUserInputViewModel get() = unused()
    override val suggestSubagentTask: SuggestSubagentTaskViewModel get() = unused()
    override val state: StateFlow<AgentStateValue> get() = unused()
    override val running: StateFlow<Boolean> get() = unused()
    override val latestIndex: StateFlow<Int> get() = unused()
    override val tokenCount: StateFlow<Long?> get() = unused()
    override val pendingSteer: StateFlow<List<StableIndexEvent.Steerable>> get() = unused()
    override val historyAction: StateFlow<AgentHistoryActionState> get() = unused()
    override val notification: StateFlow<AgentNotification?> get() = unused()
    override val lifecycle: StateFlow<AgentLifecycleState> get() = unused()
    override val settings: StateFlow<KodexAgentSettings> get() = unused()
    override val models: StateFlow<List<ModelInfo>> get() = unused()
    override suspend fun updateModel(model: OpenAiModelId): Unit = unused()
    override suspend fun updateWorkingDirectory(workingDirectory: Path): Unit = unused()
    override suspend fun updateReasoningEffort(reasoningEffort: ReasoningEffort): Unit = unused()
    override suspend fun updateServiceTier(serviceTier: ServiceTier): Unit = unused()
    override suspend fun updateRequestUserInputMode(mode: RequestUserInputMode): Unit = unused()
    override suspend fun updateModelConfiguration(model: OpenAiModelId, reasoningEffort: ReasoningEffort, serviceTier: ServiceTier): Unit = unused()
    override suspend fun submit(content: List<ContentItem>): Unit = unused()
    override fun resume(): Unit = unused()
    override fun cancel(): Unit = unused()
    override fun clearPending(): Unit = unused()
    override fun forceCompact(): Unit = unused()
    override suspend fun renameThread(threadName: String): Unit = unused()
    override fun requestHistoryRevert(untilExclusive: Int, expectedGeneration: Long): Long = unused()
    override suspend fun revertHistory(untilExclusive: Int, expectedGeneration: Long): Unit = unused()
    override fun dismissHistoryRevert(requestId: Long): Unit = unused()
    override fun confirmHistoryRevert(requestId: Long): Unit = unused()
    override fun dismissNotification(notificationId: Long): Unit = unused()
    private fun unused(): Nothing = error("Unexpected Agent API in Sidebar fixture")
}

private fun OffscreenScene.shellNodes(index: Int) = nodes().filter {
    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Shell session $index") == true
}

private fun OffscreenScene.secondaryShell(index: Int) {
    val position = shellNodes(index).single().boundsInWindow.center
    scene.sendPointerEvent(PointerEventType.Press, position, buttons = PointerButtons(isSecondaryPressed = true), button = PointerButton.Secondary)
    scene.sendPointerEvent(PointerEventType.Release, position, buttons = PointerButtons(), button = PointerButton.Secondary)
}
