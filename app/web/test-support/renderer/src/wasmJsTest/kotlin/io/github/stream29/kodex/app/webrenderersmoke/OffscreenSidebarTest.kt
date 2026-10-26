@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.app.agent.contract.*
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationViewModel
import io.github.stream29.kodex.app.ui.compose.rememberComposeSessionSidebarRenderer
import io.github.stream29.kodex.cli.app.*
import io.github.stream29.kodex.cli.settings.SidebarContent
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.rpc.models.AgentStateValue
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.Path
import kotlin.test.*

val offscreenSidebarTest by testSuite {
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
            assertEquals(0, first.closeCount + second.closeCount + retained.closeCount + agent.closeCount)
            scene.frame()
        } finally { scene.close() }
        assertEquals(0, first.closeCount + second.closeCount + retained.closeCount + agent.closeCount)
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
            scene.click("History Index")
            scene.settle { scene.hasText("● Row 3") && scene.shellNodes(1).isEmpty() }
            assertEquals(listOf(SessionSidebarSide.Left to SidebarContent.HistoryIndex), choices)
            scene.clickDescription("Sidebar content left")
            scene.settle { scene.hasText("None") }
            scene.click("None")
            scene.settle { !scene.hasText("● Row 3") }
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
