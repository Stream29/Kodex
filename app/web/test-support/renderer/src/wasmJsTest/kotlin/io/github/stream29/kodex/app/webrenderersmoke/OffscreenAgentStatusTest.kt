package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingRequestUserInputToolEvent
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationViewModel
import io.github.stream29.kodex.app.ui.compose.*
import io.github.stream29.kodex.cli.app.*
import io.github.stream29.kodex.rpc.models.AgentStateValue
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import kotlinx.io.files.Path
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.math.abs

val offscreenAgentStatusTest by testSuite {
    test("measured status items wrap in order with settings on the first row") { runtimeScene { _, child, _ ->
        val agent = StatusTestAgent(child)
        var width by mutableStateOf(1200.dp)
        val scene = OffscreenScene.create(width = 1300) {
            Box(Modifier.width(width)) {
                AgentRuntimeStatusContent(
                    agent, AgentStateValue.AssistantMessage, false,
                    KodexAgentSettings(model = child.state.value.configuration.model, cwd = Path("/cwd")),
                    123, rememberComposeAgentStatusRenderer(), {}, {},
                )
            }
        }
        try {
            scene.settle { scene.hasText("Resume") && scene.hasText("Settings") }
            val wideResume = scene.text("Resume").boundsInWindow
            val wideSettings = scene.text("Settings").boundsInWindow
            assertEquals(wideResume.top, wideSettings.top)
            assertTrue(wideSettings.left > wideResume.right)
            width = 200.dp
            scene.settle { scene.text("Compact").boundsInWindow.top > scene.text("Resume").boundsInWindow.top }
            val resume = scene.text("Resume").boundsInWindow
            val compact = scene.text("Compact").boundsInWindow
            val model = scene.text("test-model max fast").boundsInWindow
            val settings = scene.text("Settings").boundsInWindow
            assertTrue(settings.top <= resume.top)
            assertTrue(resume.top < compact.top)
            assertTrue(compact.top < model.top)
        } finally { scene.close() }
    } }
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
    test("live separator toggles controlled latest action and rounds shared duration") { offscreenTest {
        var duration by mutableStateOf<kotlin.time.Duration?>(null)
        var showLatest by mutableStateOf(false)
        var latest = 0
        val scene = OffscreenScene.create {
            AgentHistorySeparatorContent(duration, showLatest, { latest++; showLatest = false }, rememberComposeAgentHistorySeparatorRenderer())
        }
        try {
            scene.frame()
            assertFalse(scene.hasDescription("Scroll to latest"))
            duration = 1_500.milliseconds
            showLatest = true
            scene.settle { scene.hasText("Worked for 2s") && scene.hasDescription("Scroll to latest") }
            scene.clickDescription("Scroll to latest")
            scene.settle { latest == 1 && !scene.hasDescription("Scroll to latest") }
            assertTrue(scene.hasText("Worked for 2s"))
            duration = null
            scene.settle { !scene.hasText("Worked for 2s") }
            scene.frame()
        } finally { scene.close() }
    } }
    for (width in listOf(120, 360)) for (scale in listOf(1f, 1.5f)) {
        test("latest action is centered and separates line width=$width scale=$scale") { offscreenTest {
            var duration by mutableStateOf<kotlin.time.Duration?>(null)
            var showLatest by mutableStateOf(false)
            var clicks = 0
            val scene = OffscreenScene.create(width = width) {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                    AgentHistorySeparatorContent(duration, showLatest, { clicks++ }, rememberComposeAgentHistorySeparatorRenderer())
                }
            }
            fun tagged(tag: String) = scene.nodes().first {
                it.config.getOrNull(SemanticsProperties.TestTag) == tag
            }
            fun button() = scene.nodes().first {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Scroll to latest") == true
            }
            try {
                scene.frame()
                assertFalse(scene.hasDescription("Scroll to latest"))
                assertFalse(scene.hasText("Worked for 2s"))
                duration = 1_500.milliseconds
                scene.settle { scene.hasText("Worked for 2s") }
                assertFalse(scene.hasDescription("Scroll to latest"))
                showLatest = true
                scene.settle { scene.hasDescription("Scroll to latest") }
                val action = button().boundsInRoot
                val label = scene.text("Worked for 2s").boundsInRoot
                val left = tagged("history-separator-left").boundsInRoot
                val right = tagged("history-separator-right").boundsInRoot
                assertTrue(abs(action.center.x - width / 2f) <= 1f)
                assertTrue(label.right <= action.left)
                assertTrue(left.right <= action.left && right.left >= action.right)
                assertTrue(left.width > 0f && right.width > 0f)
                scene.key(Key.Tab)
                scene.settle { button().config.getOrNull(SemanticsProperties.Focused) == true }
                scene.key(Key.Enter)
                scene.settle { clicks == 1 }
                scene.clickDescription("Scroll to latest")
                assertEquals(2, clicks)
                duration = null
                scene.settle { !scene.hasText("Worked for 2s") }
                assertTrue(scene.hasDescription("Scroll to latest"))
                showLatest = false
                scene.settle { !scene.hasDescription("Scroll to latest") }
            } finally { scene.close() }
        } }
    }
}

private class StatusTestAgent(override val runtimeConfiguration: RuntimeConfigurationViewModel) : SidebarTestAgent() {
    val commands = mutableListOf<String>()
    override fun resume() { commands += "resume" }
    override fun cancel() { commands += "cancel" }
    override fun clearPending() { commands += "clear" }
    override fun forceCompact() { commands += "compact" }
}
