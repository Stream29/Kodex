package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingRequestUserInputToolEvent
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationViewModel
import io.github.stream29.kodex.app.ui.compose.*
import io.github.stream29.kodex.cli.app.*
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.rpc.models.AgentStateValue
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import kotlinx.io.files.Path
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

val offscreenAgentStatusTest by testSuite {
    test("shared toolbar branches and exact commands retain borrowed runtime child") { runtimeScene { ports, child, _ ->
        val agent = StatusTestAgent(child)
        var phase: AgentStateValue by mutableStateOf(AgentStateValue.AssistantMessage)
        var running by mutableStateOf(false)
        var tokens: Long? by mutableStateOf(null)
        var browse = 0
        var settings = 0
        val scene = OffscreenScene.create {
            AgentRuntimeStatusContent(
                agent, phase, running, KodexAgentSettings(model = ports.configuration.value.model, cwd = Path("/opaque/backend/cwd")), tokens,
                rememberComposeAgentStatusRenderer(), { browse++ }, { settings++ },
            )
        }
        try {
            scene.settle { scene.hasText("Resume") && scene.hasDescription("Runtime model: test-model max fast") }
            scene.click("Resume")
            scene.click("Compact")
            assertEquals(listOf("resume", "compact"), agent.commands)
            scene.clickDescription("Working directory: /opaque/backend/cwd")
            scene.click("Settings")
            assertEquals(1, browse)
            assertEquals(1, settings)
            for (blocked in listOf(AgentStateValue.Compacting, AgentStateValue.ExternalWrite, AgentStateValue.RequestResponse.Started)) {
                phase = blocked
                scene.settle { scene.hasText("Compact") && scene.nodes().any { it.config.getOrNull(SemanticsProperties.Disabled) != null } }
                scene.click("Compact")
                assertEquals(listOf("resume", "compact"), agent.commands)
            }
            phase = AgentStateValue.ToolPending(listOf(PendingRequestUserInputToolEvent(
                "question", arguments = RequestUserInputArgs(listOf(RequestUserInputQuestion("id", "header", "question"))),
            )))
            tokens = 0L
            scene.settle { scene.hasText("Clear pending") && scene.hasText("0t") }
            scene.click("Clear pending")
            running = true
            phase = AgentStateValue.Compacting
            tokens = 123L
            scene.settle { scene.hasText("Stop") && !scene.hasText("Compact") && scene.hasText("123t") }
            scene.click("Stop")
            assertEquals(listOf("resume", "compact", "clear", "cancel"), agent.commands)
            tokens = null
            scene.settle { !scene.hasText("123t") }
            assertTrue(ports.tuples.isEmpty())
            scene.frame()
        } finally { scene.close() }
        assertEquals(0, agent.closeCount)
        assertFalse(child.state.value.closed)
    } }
    test("captured toolbar command never targets a replacement owner") { runtimeScene { _, child, _ ->
        val first = StatusTestAgent(child)
        val second = StatusTestAgent(child)
        var selected by mutableStateOf(first)
        var rendered: StatusTestAgent? = null
        var captured: (() -> Unit)? = null
        val scene = OffscreenScene.create {
            rendered = selected
            val graphical = rememberComposeAgentStatusRenderer()
            val renderer = object : AgentStatusRenderer by graphical {
                @Composable
                override fun Action(label: String, enabled: Boolean, onClick: () -> Unit) {
                    if (label == "Resume" && selected === first) captured = onClick
                    graphical.Action(label, enabled, onClick)
                }
            }
            AgentRuntimeStatusContent(
                selected, AgentStateValue.AssistantMessage, false, KodexAgentSettings(model = child.state.value.configuration.model, cwd = Path("/opaque")), null,
                renderer, {}, {},
            )
        }
        try {
            scene.settle { captured != null }
            val old = assertNotNull(captured)
            selected = second
            scene.settle { rendered === second }
            old()
            scene.click("Resume")
            assertEquals(listOf("resume"), first.commands)
            assertEquals(listOf("resume"), second.commands)
        } finally { scene.close() }
        assertEquals(0, first.closeCount + second.closeCount)
    } }
    test("live separator toggles controlled Latest and rounds shared duration") { offscreenTest {
        var duration by mutableStateOf<kotlin.time.Duration?>(null)
        var showLatest by mutableStateOf(false)
        var latest = 0
        val scene = OffscreenScene.create {
            AgentHistorySeparatorContent(duration, showLatest, { latest++; showLatest = false }, rememberComposeAgentHistorySeparatorRenderer())
        }
        try {
            scene.frame()
            assertFalse(scene.hasText("Latest"))
            duration = 1_500.milliseconds
            showLatest = true
            scene.settle { scene.hasText("Worked for 2s") && scene.hasText("Latest") }
            scene.click("Latest")
            scene.settle { latest == 1 && !scene.hasText("Latest") }
            assertTrue(scene.hasText("Worked for 2s"))
            duration = null
            scene.settle { !scene.hasText("Worked for 2s") }
            scene.frame()
        } finally { scene.close() }
    } }
}

private class StatusTestAgent(override val runtimeConfiguration: RuntimeConfigurationViewModel) : SidebarTestAgent() {
    val commands = mutableListOf<String>()
    override fun resume() { commands += "resume" }
    override fun cancel() { commands += "cancel" }
    override fun clearPending() { commands += "clear" }
    override fun forceCompact() { commands += "compact" }
}
