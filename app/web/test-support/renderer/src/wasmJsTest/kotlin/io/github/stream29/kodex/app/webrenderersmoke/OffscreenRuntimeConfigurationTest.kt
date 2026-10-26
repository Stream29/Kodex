package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.runtimeconfiguration.*
import io.github.stream29.kodex.app.ui.compose.rememberComposeRuntimeConfigurationRenderer
import io.github.stream29.kodex.cli.runtimeconfiguration.*
import io.github.stream29.kodex.openai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

val offscreenRuntimeConfigurationTest by testSuite {
    test("runtime navigation keeps selected focus and writes one tuple only at tier leaf") { runtimeScene { ports, child, owner ->
        val scene = OffscreenScene.create { RuntimeConfigurationContent(child, rememberComposeRuntimeConfigurationRenderer()) }
        try {
            scene.settle { scene.hasDescription("Runtime model: test-model max fast") }
            scene.clickDescription("Runtime model: test-model max fast")
            scene.settle { scene.focusedDescription("Runtime model: test-model") }
            scene.clickDescription("Runtime model: test-model")
            scene.settle { scene.focusedDescription("Runtime model: max") }
            scene.clickDescription("Runtime model: max")
            scene.settle { scene.focusedDescription("Runtime model: fast") }
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
