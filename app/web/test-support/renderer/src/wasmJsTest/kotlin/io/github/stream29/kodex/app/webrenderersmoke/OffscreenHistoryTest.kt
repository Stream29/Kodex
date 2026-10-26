@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CleanIndexEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingFunctionToolEvent
import io.github.stream29.kodex.agentstorage.contract.IndexVersioned
import io.github.stream29.kodex.agentstorage.contract.KodexAgentStorage
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.app.history.contract.*
import io.github.stream29.kodex.app.history.contract.item.HistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemViewModel
import io.github.stream29.kodex.cli.history.AgentHistorySource
import io.github.stream29.kodex.cli.history.createAgentHistoryViewModel
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.app.ui.UiText
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlin.test.*
import kotlin.time.Instant

val offscreenHistoryTest by testSuite {
    test("real History owner pages around a raster viewport and acknowledges exact item and Latest effects") { offscreenTest {
        val stored = InMemoryKodexAgentStorage.empty()
        for (index in 1..640) {
            stored.index[index] = StableUserMessage(listOf(ContentItem.InputText("Offscreen history $index")))
            stored.timestamp[index] = Instant.fromEpochMilliseconds(index * 1_000L)
        }
        val reads = mutableListOf<Int>()
        val source = object : KodexAgentStorage by stored {
            override val index = object : IndexVersioned<CleanIndexEntry> by stored.index {
                override suspend fun getExact(index: Int): CleanIndexEntry? {
                    reads += index
                    return stored.index.getExact(index)
                }
            }
        }
        val owner = CoroutineScope(currentCoroutineContext() + Job(currentCoroutineContext()[Job]))
        val model = createAgentHistoryViewModel(
            AgentHistorySource(source, MutableStateFlow(640), MutableStateFlow(KodexAgentStateValue.UserMessage)),
            owner, MutableStateFlow(false),
        )
        var visible = emptyList<HistoryItemViewModel>()
        var reportedWindow: HistoryItemWindow? = null
        val acknowledgments = mutableListOf<HistoryScrollEffect>()
        val observed = object : AgentHistoryViewModel by model {
            override fun reportViewport(window: HistoryItemWindow, visibleItems: List<HistoryItemViewModel>) {
                reportedWindow = window
                visible = visibleItems
                model.reportViewport(window, visibleItems)
            }
            override fun acknowledgeScrollEffect(effect: HistoryScrollEffect) {
                assertSame(model.pendingScrollEffect.value, effect)
                assertEquals(model.historyItems.value.generation, effect.generation)
                acknowledgments += effect
                model.acknowledgeScrollEffect(effect)
            }
        }
        var output: OffscreenScene? = null
        try {
            val retained = FixtureHistoryListState(observed)
            var mounted by mutableStateOf(true)
            val rendered = OffscreenScene.create {
                if (mounted) FixtureHistoryList(observed, targetIndex = 1, state = retained)
                else UiText("Paused History fixture")
            }.also { output = it }
            rendered.settle("initial latest rows") { model.loadState.value == AgentHistoryLoadState.Ready && rendered.hasText("Offscreen history 640") }
            val initial = model.historyItems.value
            assertTrue(initial.size in 128..256)
            assertTrue(visible.size in 1..20)
            assertSame(initial, reportedWindow)
            assertTrue(reads.isNotEmpty() && reads.min() > 400, "Initial paint must not read all 640 payloads.")
            val originalChildren = List(initial.size) { initial.peek(it) }
                .filterIsInstance<MessageHistoryItemViewModel>().associateBy { it.index }
            val position = rendered.text("Offscreen history 640").boundsInRoot.center
            for (step in 1..100) {
                rendered.scene.sendPointerEvent(
                    PointerEventType.Scroll, position, scrollDelta = Offset(0f, -800f),
                )
                rendered.advance()
                delay(1)
                if (visible.filterIsInstance<MessageHistoryItemViewModel>().any { it.index < 380 }) break
            }
            rendered.settle("wheel-driven older window") {
                visible.filterIsInstance<MessageHistoryItemViewModel>().any { it.index < 380 } &&
                    model.loadState.value == AgentHistoryLoadState.Ready
            }
            val older = model.historyItems.value
            assertTrue(older.hasNewer)
            assertFalse(model.followsLatest.value)
            assertTrue(older.size <= 256 && visible.size in 1..20)
            List(older.size) { older.peek(it) }.filterIsInstance<MessageHistoryItemViewModel>().forEach { item ->
                originalChildren[item.index]?.let { assertSame(it, item, "Retained rows keep their exact child.") }
            }
            assertTrue(reads.min() < 380)

            val previousAnchor = visible.first()
            mounted = false
            rendered.settle("temporary History surface unmount") { rendered.hasText("Paused History fixture") && visible.isEmpty() }
            mounted = true
            rendered.settle("retained product widget anchor") { visible.any { it === previousAnchor } }
            assertFalse(model.followsLatest.value, "Remount must not replace the retained position with Latest.")

            rendered.click("Fixture seeded target")
            rendered.settle("exact first-index effect") { acknowledgments.isNotEmpty() && rendered.hasText("Offscreen history 1") }
            val exact = acknowledgments.last()
            assertEquals(1, assertIs<MessageHistoryItemViewModel>(
                assertIs<HistoryScrollTarget.Item>(exact.target).item,
            ).index)
            assertNull(model.pendingScrollEffect.value)
            assertTrue(visible.filterIsInstance<MessageHistoryItemViewModel>().any { it.index == 1 })

            rendered.click("Fixture Latest")
            rendered.settle("Latest effect") { acknowledgments.size == 2 && rendered.hasText("Offscreen history 640") }
            assertSame(HistoryScrollTarget.Latest, acknowledgments.last().target)
            assertNull(model.pendingScrollEffect.value)
            assertTrue(model.followsLatest.value && !model.historyItems.value.hasNewer)
            assertTrue(model.historyItems.value.size <= 256)

            rendered.close()
            output = null
            assertTrue(visible.isEmpty(), "Unmount reports an empty viewport without closing its borrowed owner.")
            model.requestScrollToStorageIndex(2)
            withTimeout(5_000) { while (model.pendingScrollEffect.value == null) delay(1) }
            assertIs<HistoryScrollTarget.Item>(model.pendingScrollEffect.value?.target)
        } finally {
            output?.close()
            model.close()
            owner.coroutineContext.job.cancelAndJoin()
        }
    } }

    test("product list shares transient order, exact prefixed scroll and destructive empty-window replacement") { offscreenTest {
        val stored = InMemoryKodexAgentStorage.empty()
        stored.index[1] = StableUserMessage(listOf(ContentItem.InputText("Committed row")))
        stored.timestamp[1] = Instant.fromEpochMilliseconds(1_000)
        val latest = MutableStateFlow(1)
        val owner = CoroutineScope(currentCoroutineContext() + Job(currentCoroutineContext()[Job]))
        val model = createAgentHistoryViewModel(
            AgentHistorySource(stored, latest, MutableStateFlow(KodexAgentStateValue.UserMessage)), owner, MutableStateFlow(false),
        )
        val pending = MutableStateFlow(listOf(
            PendingFunctionToolEvent("first", name = "first", arguments = JsonObject(emptyMap())),
            PendingFunctionToolEvent("second", name = "second", arguments = JsonObject(emptyMap())),
        ))
        val streaming = MutableStateFlow<HistoryStreamingItem?>(HistoryStreamingItem.Started)
        val displayed = object : AgentHistoryViewModel by model {
            override val pendingTools = pending
            override val streamingItem = streaming
        }
        val diagnostics = FixtureHistoryListState(displayed)
        val scene = OffscreenScene.create { FixtureHistoryList(displayed, targetIndex = 1, state = diagnostics) }
        try {
            scene.settle("transient and committed rows") {
                scene.hasText("Starting response…") && scene.hasText("Committed row") &&
                    scene.hasDescription("History tool: Running first") && scene.hasDescription("History tool: Running second")
            }
            fun toolY(name: String): Float = scene.nodes().first {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("History tool: Running $name") == true
            }.boundsInWindow.center.y
            assertTrue(scene.text("Starting response…").boundsInWindow.center.y > toolY("second"))
            assertTrue(toolY("second") > toolY("first"))
            assertTrue(toolY("first") > scene.text("Committed row").boundsInWindow.center.y)
            scene.click("Fixture seeded target")
            scene.settle("exact item after transient prefix") { diagnostics.acknowledgments == 1 && model.pendingScrollEffect.value == null }
            scene.click("Fixture Latest")
            scene.settle("exact newest transient key") { diagnostics.acknowledgments == 2 && model.pendingScrollEffect.value == null }
            pending.value = emptyList()
            streaming.value = null
            scene.settle { !scene.hasText("Starting response…") && !scene.hasDescription("History tool: Running first") }
            val generation = model.historyItems.value.generation
            stored.index.revert(1)
            stored.timestamp.revert(1)
            latest.value = 0
            scene.settle("replacement uses a new bounded window") {
                model.loadState.value == AgentHistoryLoadState.Ready && model.historyItems.value.generation > generation &&
                    scene.hasText("No conversation history items")
            }
            assertFalse(scene.hasText("Committed row"))
            scene.click("Fixture Latest")
            scene.settle("empty newest marker acknowledgment") { diagnostics.acknowledgments == 3 && model.pendingScrollEffect.value == null }
            scene.frame()
        } finally {
            scene.close()
            model.close()
            owner.coroutineContext.job.cancelAndJoin()
        }
    } }
}
