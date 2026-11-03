package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.input.key.Key
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.runtimeconfiguration.*
import io.github.stream29.kodex.app.ui.compose.rememberComposeRuntimeConfigurationRenderer
import io.github.stream29.kodex.cli.runtimeconfiguration.*
import io.github.stream29.kodex.openai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

val offscreenRuntimeConfigurationTest by testSuite {
    for ((width, height) in listOf(240 to 120, 320 to 160)) {
        for (fontScale in listOf(1f, 1.5f)) {
            test("short narrow cascade scrolls focused rows and commits one exact leaf ${width}x$height font=$fontScale") { offscreenTest {
                val commands = mutableListOf<Int>()
                val models = List(20) { index ->
                    RuntimeMenuEntry.Branch(
                        "model-$index", "Model ${index.toString().padStart(2, '0')}", index == 0, "effort",
                    ) {
                        listOf(RuntimeMenuEntry.Branch("effort", "Effort", true, "tier") {
                            listOf(RuntimeMenuEntry.Command("tier", "Tier", true) { commands += index })
                        })
                    }
                }
                val menu = RuntimeConfigurationMenu(
                    "current", models, "mode", listOf(RuntimeMenuEntry.Command("mode", "Mode", true) {}),
                )
                val scene = OffscreenScene.create(width = width, height = height) {
                    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                        rememberComposeRuntimeConfigurationRenderer().Fields(menu, enabled = true)
                    }
                }
                try {
                    scene.settle { scene.hasDescription("Runtime model: current") }
                    scene.clickDescription("Runtime model: current")
                    scene.settle { scene.focusedDescription("Runtime model: Model 00") }
                    scene.key(Key.MoveEnd)
                    scene.settle { scene.focusedDescription("Runtime model: Model 19") }
                    val last = scene.nodes().first {
                        it.config.getOrNull(SemanticsProperties.Focused) == true &&
                            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Runtime model: Model 19") == true
                    }.boundsInWindow
                    assertTrue(last.top >= 0 && last.bottom <= height, "The focused row must scroll into the vertical viewport: $last")
                    scene.key(Key.DirectionRight)
                    scene.settle { scene.focusedDescription("Runtime model: Effort") }
                    assertTrue(scene.hasDescription("Runtime model: Model 19"))
                    scene.key(Key.DirectionRight)
                    scene.settle { scene.focusedDescription("Runtime model: Tier") }
                    assertTrue(scene.hasDescription("Runtime model: Model 19"))
                    assertTrue(scene.hasDescription("Runtime model: Effort"))
                    assertTrue(commands.isEmpty())
                    scene.key(Key.Enter)
                    scene.settle { commands.size == 1 }
                    assertEquals(listOf(19), commands)
                } finally { scene.close() }
            } }
        }
    }
    for (dark in listOf(false, true)) {
        for (fontScale in listOf(1f, 1.5f)) {
            test("cascade focus is visible and submits the focused command dark=$dark font=$fontScale") { offscreenTest {
                val commands = mutableListOf<String>()
                val menu = RuntimeConfigurationMenu(
                    "current", listOf(
                        RuntimeMenuEntry.Command("alpha", "Alpha", true) { commands += "alpha" },
                        RuntimeMenuEntry.Command("beta", "Beta", false) { commands += "beta" },
                    ),
                    "mode", listOf(RuntimeMenuEntry.Command("mode", "Mode", true) { commands += "mode" }),
                )
                val scene = OffscreenScene.create(width = 480, height = 240) {
                    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                            rememberComposeRuntimeConfigurationRenderer().Fields(menu, enabled = true)
                        }
                    }
                }
                try {
                    scene.settle { scene.hasDescription("Runtime model: current") }
                    scene.clickDescription("Runtime model: current")
                    scene.settle { scene.focusedDescription("Runtime model: Alpha") }
                    repeat(30) { scene.advance() }
                    val first = scene.frame()
                    assertTrue(first.contentEquals(scene.frame()), "The first focused raster must be stable.")
                    scene.key(Key.DirectionDown)
                    scene.settle { scene.focusedDescription("Runtime model: Beta") }
                    repeat(30) { scene.advance() }
                    val second = scene.frame()
                    assertTrue(second.contentEquals(scene.frame()), "The second focused raster must be stable.")
                    assertFalse(first.contentEquals(second), "Moving focus must change the visible cascade raster.")
                    assertTrue(commands.isEmpty())
                    scene.key(Key.Enter)
                    scene.settle { commands.size == 1 }
                    assertEquals(listOf("beta"), commands)
                } finally { scene.close() }
            } }
        }
    }
    test("runtime cascade uses compact rows and preserves readable long labels") { runtimeScene { ports, child, _ ->
        val longLabel = "a-very-long-provider-model-name-with-many-segments-".repeat(3)
        ports.models.value = listOf(ModelInfo(
            OpenAiModelId(longLabel), "Long model",
            supportedReasoningLevels = listOf(ReasoningEffortPreset(ReasoningEffort.Low, "Low")),
        ))
        val scene = OffscreenScene.create(width = 800, height = 240) {
            RuntimeConfigurationContent(child, rememberComposeRuntimeConfigurationRenderer())
        }
        try {
            val trigger = "Runtime model: test-model max fast"
            scene.settle { scene.hasDescription(trigger) }
            scene.clickDescription(trigger)
            scene.settle { scene.hasDescription("Runtime model: first-model") || scene.hasDescription("Runtime model: $longLabel") }
            val menu = scene.nodes().first {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Runtime model menu") == true
            }.boundsInWindow
            val row = scene.nodes().first {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Runtime model: $longLabel") == true
            }.boundsInWindow
            assertTrue(menu.width in 120f..220f, "Compact column must not stay at the original 240dp.")
            assertTrue(menu.width >= 200f, "A long label should use the available bounded column width.")
            assertTrue(row.height >= 32f, "A long wrapped label must remain an accessible target.")
            assertTrue(row.height > 32f, "Long labels must wrap instead of being clipped to one compact row.")
            scene.frame()
        } finally { scene.close() }
    } }
    test("runtime cascade keeps short rows compact with exact semantics") { offscreenTest {
        var calls = 0
        val menu = RuntimeConfigurationMenu(
            "short", listOf(RuntimeMenuEntry.Command("short", "Short", true) { calls++ }),
            "mode", listOf(RuntimeMenuEntry.Command("mode", "Mode", true) { calls++ }),
        )
        val scene = OffscreenScene.create(width = 500, height = 240) {
            rememberComposeRuntimeConfigurationRenderer().Fields(menu, enabled = true)
        }
        try {
            scene.settle { scene.hasDescription("Runtime model: short") }
            scene.clickDescription("Runtime model: short")
            scene.settle { scene.hasDescription("Runtime model: Short") }
            val row = scene.nodes().first {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Runtime model: Short") == true
            }
            val column = scene.nodes().first {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Runtime model menu") == true
            }
            assertTrue(column.boundsInWindow.width in 120f..150f, "Short labels should not reserve the maximum column width.")
            assertTrue(row.boundsInWindow.height in 32f..40f)
            assertEquals(true, row.config.getOrNull(SemanticsProperties.Selected))
            assertEquals(0, calls)
            scene.clickDescription("Runtime model: Short")
            scene.settle { calls == 1 }
        } finally { scene.close() }
    } }
    test("both runtime triggers preserve measured focus and keyboard menu return") { runtimeScene { ports, child, _ ->
        val scene = OffscreenScene.create(width = 500, height = 240) {
            RuntimeConfigurationContent(child, rememberComposeRuntimeConfigurationRenderer())
        }
        try {
            val model = "Runtime model: test-model max fast"
            val questions = "Questions: ask user"
            scene.settle { scene.hasDescription(model) && scene.hasDescription(questions) }
            for (description in listOf(model, questions)) {
                val trigger = scene.nodes().first {
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true
                }
                assertTrue(trigger.boundsInWindow.width >= 32f)
                assertTrue(trigger.boundsInWindow.height >= 32f)
                scene.clickDescription(description)
                scene.settle { scene.hasDescription(if (description == model) "Runtime model: test-model" else "Questions: no question") }
                scene.escape()
                scene.settle { scene.focusedDescription(description) }
                scene.key(Key.Enter)
                scene.settle { scene.hasDescription(if (description == model) "Runtime model: test-model" else "Questions: no question") }
                scene.escape()
                scene.settle { scene.focusedDescription(description) }
            }
            assertTrue(ports.tuples.isEmpty())
            assertTrue(ports.modes.isEmpty())
        } finally { scene.close() }
    } }
    test("runtime navigation keeps selected focus and writes one tuple only at tier leaf") { runtimeScene { ports, child, owner ->
        val scene = OffscreenScene.create { RuntimeConfigurationContent(child, rememberComposeRuntimeConfigurationRenderer()) }
        try {
            scene.settle { scene.hasDescription("Runtime model: test-model max fast") }
            scene.clickDescription("Runtime model: test-model max fast")
            scene.settle { scene.focusedDescription("Runtime model: test-model") }
            scene.clickDescription("Runtime model: test-model")
            scene.settle { scene.focusedDescription("Runtime model: max") }
            assertTrue(scene.hasDescription("Runtime model: test-model"))
            scene.clickDescription("Runtime model: max")
            scene.settle { scene.focusedDescription("Runtime model: fast") }
            assertTrue(scene.hasDescription("Runtime model: test-model"))
            assertTrue(scene.hasDescription("Runtime model: max"))
            assertTrue(ports.tuples.isEmpty())
            scene.escape()
            scene.settle { scene.hasDescription("Runtime model: max") && !scene.hasDescription("Runtime model: fast") }
            assertTrue(ports.tuples.isEmpty())
            scene.clickDescription("Runtime model: max")
            scene.settle { scene.hasDescription("Runtime model: default") }
            scene.clickDescription("Runtime model: default")
            scene.settle { ports.tuples.size == 1 }
            assertEquals<List<Triple<OpenAiModelId, ReasoningEffort, ServiceTier>>>(
                listOf(Triple(OpenAiModelId("test-model"), ReasoningEffort.Max, ServiceTier.Default)), ports.tuples,
            )
            assertEquals(ServiceTier.Fast, ports.configuration.value.tier)
            assertTrue(scene.hasDescription("Runtime model: test-model max fast"))
            scene.clickDescription("Questions: ask user")
            scene.settle { scene.hasDescription("Questions: no question") }
            scene.clickDescription("Questions: no question")
            scene.settle { ports.modes.size == 1 }
            assertEquals(listOf(RequestUserInputMode.NoQuestion), ports.modes)
            assertEquals(RequestUserInputMode.AskUser, ports.configuration.value.requestUserInputMode)
            assertTrue(owner.coroutineContext.job.isActive)
            scene.frame()
        } finally { scene.close() }
        assertFalse(child.state.value.closed)
    } }
    test("missing metadata displays custom saved effort and tier without automatic correction") { runtimeScene { ports, child, _ ->
        ports.configuration.value = RuntimeConfiguration(
            OpenAiModelId("missing"), ReasoningEffort.Custom("provider-effort"), ServiceTier.Flex, RequestUserInputMode.AskUser,
        )
        ports.models.value = emptyList()
        val scene = OffscreenScene.create { RuntimeConfigurationContent(child, rememberComposeRuntimeConfigurationRenderer()) }
        try {
            scene.settle { scene.hasDescription("Runtime model: missing provider-effort flex") }
            assertTrue(ports.tuples.isEmpty())
            scene.clickDescription("Runtime model: missing provider-effort flex")
            scene.settle { scene.hasDescription("Runtime model: missing") }
            scene.clickDescription("Runtime model: missing")
            scene.settle { scene.hasDescription("Runtime model: provider-effort") }
            scene.clickDescription("Runtime model: provider-effort")
            scene.settle { scene.hasDescription("Runtime model: default") }
            assertFalse(scene.hasDescription("Runtime model: flex"))
            scene.clickDescription("Runtime model: default")
            scene.settle { ports.tuples.size == 1 }
            assertEquals<List<Triple<OpenAiModelId, ReasoningEffort, ServiceTier>>>(
                listOf(Triple(OpenAiModelId("missing"), ReasoningEffort.Custom("provider-effort"), ServiceTier.Default)), ports.tuples,
            )
            assertEquals(ServiceTier.Flex, ports.configuration.value.tier)
        } finally { scene.close() }
    } }
    for (replace in listOf(false, true)) {
        test("runtime composition owns waits but not borrowed child replace=$replace") { runtimeScene { ports, child, owner ->
            ports.gate = CompletableDeferred()
            val replacementPorts = RuntimePorts()
            val replacement = createRuntimeConfigurationViewModel(replacementPorts, owner)
            val selected = MutableStateFlow(child)
            val scene = OffscreenScene.create {
                RuntimeConfigurationContent(selected.collectAsState().value, rememberComposeRuntimeConfigurationRenderer())
            }
            var sceneClosed = false
            try {
                scene.settle { scene.hasDescription("Runtime model: test-model max fast") }
                scene.clickDescription("Runtime model: test-model max fast")
                scene.settle { scene.hasDescription("Runtime model: test-model") }
                scene.clickDescription("Runtime model: test-model")
                scene.settle { scene.hasDescription("Runtime model: max") }
                scene.clickDescription("Runtime model: max")
                scene.settle { scene.hasDescription("Runtime model: default") }
                scene.clickDescription("Runtime model: default")
                scene.settle { ports.tuples.size == 1 }
                if (replace) {
                    selected.value = replacement
                    scene.settle { ports.cancelled == 1 }
                } else {
                    scene.close()
                    sceneClosed = true
                    withTimeout(5_000) { while (ports.cancelled == 0) delay(1) }
                }
                assertEquals(1, ports.cancelled)
                assertEquals(0, ports.completed)
                assertFalse(child.state.value.closed)
                assertFalse(replacement.state.value.closed)
                assertTrue(replacementPorts.tuples.isEmpty())
            } finally {
                if (!sceneClosed) scene.close()
                replacement.close()
            }
        } }
    }
    test("runtime menu builds only opened descendants and leaf captures the displayed tuple") {
        var effortReads = 0
        var tierReads = 0
        val efforts = object : AbstractList<ReasoningEffort>() {
            private val values = listOf(ReasoningEffort.Low, ReasoningEffort.Medium, ReasoningEffort.High, ReasoningEffort.Max)
            override val size = values.size
            override fun get(index: Int): ReasoningEffort { effortReads++; return values[index] }
        }
        val tiers = object : AbstractList<ServiceTier>() {
            override val size = ServiceTier.entries.size
            override fun get(index: Int): ServiceTier { tierReads++; return ServiceTier.entries[index] }
        }
        val configuration = RuntimeConfiguration(OpenAiModelId("model0"), ReasoningEffort.Max, ServiceTier.Fast, RequestUserInputMode.AskUser)
        val state = RuntimeConfigurationState(configuration, List(1_000) {
            RuntimeConfigurationModelOption(OpenAiModelId("model$it"), efforts, tiers)
        })
        val tuples = mutableListOf<Triple<OpenAiModelId, ReasoningEffort, ServiceTier>>()
        val menu = assertNotNull(runtimeConfigurationMenu(state, { model, effort, tier -> tuples += Triple(model, effort, tier) }, {}))
        assertEquals(1_000, menu.models.size)
        assertTrue(effortReads < 1_010)
        assertEquals(0, tierReads)
        val effortMenu = (menu.models.last() as RuntimeMenuEntry.Branch).children()
        assertEquals(4, effortMenu.size)
        assertEquals(0, tierReads)
        val tierMenu = (effortMenu.first() as RuntimeMenuEntry.Branch).children()
        assertEquals(ServiceTier.entries.size, tierReads)
        assertTrue(tuples.isEmpty())
        (tierMenu.last() as RuntimeMenuEntry.Command).onSelect()
        assertEquals<List<Triple<OpenAiModelId, ReasoningEffort, ServiceTier>>>(
            listOf(Triple(OpenAiModelId("model999"), ReasoningEffort.Low, ServiceTier.Ultrafast)), tuples,
        )
        assertNull(runtimeConfigurationMenu(state.copy(closed = true), { _, _, _ -> error("closed") }, { error("closed") }))
    }
}

private fun OffscreenScene.focusedDescription(description: String): Boolean = nodes().any {
    it.config.getOrNull(SemanticsProperties.Focused) == true &&
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true
}

internal suspend fun runtimeScene(
    block: suspend (RuntimePorts, RuntimeConfigurationViewModel, CoroutineScope) -> Unit,
) = offscreenTest {
    val ports = RuntimePorts()
    val owner = CoroutineScope(currentCoroutineContext() + Job(currentCoroutineContext()[Job]))
    val child = createRuntimeConfigurationViewModel(ports, owner)
    try { block(ports, child, owner) } finally { child.close(); owner.coroutineContext.job.cancelAndJoin() }
}

internal class RuntimePorts : RuntimeConfigurationDependencies {
    override val configuration = MutableStateFlow(RuntimeConfiguration(
        OpenAiModelId("test-model"), ReasoningEffort.Max, ServiceTier.Fast, RequestUserInputMode.AskUser,
    ))
    override val models = MutableStateFlow(listOf(
        ModelInfo(OpenAiModelId("first-model"), "First", supportedReasoningLevels = listOf(ReasoningEffortPreset(ReasoningEffort.Low, "Low"))),
        ModelInfo(
            OpenAiModelId("test-model"), "Test",
            supportedReasoningLevels = listOf(ReasoningEffortPreset(ReasoningEffort.Low, "Low"), ReasoningEffortPreset(ReasoningEffort.Max, "Max")),
            serviceTiers = listOf(ModelServiceTier(ServiceTier.Fast.requestValue, "Fast", "Fast")),
        ),
    ))
    val tuples = mutableListOf<Triple<OpenAiModelId, ReasoningEffort, ServiceTier>>()
    val modes = mutableListOf<RequestUserInputMode>()
    var gate: CompletableDeferred<Unit>? = null
    var cancelled = 0
    var completed = 0
    override suspend fun updateModelConfiguration(model: OpenAiModelId, effort: ReasoningEffort, tier: ServiceTier) {
        tuples += Triple(model, effort, tier)
        try { gate?.await(); completed++ } catch (failure: CancellationException) { cancelled++; throw failure }
    }
    override suspend fun updateRequestUserInputMode(mode: RequestUserInputMode) { modes += mode }
}
