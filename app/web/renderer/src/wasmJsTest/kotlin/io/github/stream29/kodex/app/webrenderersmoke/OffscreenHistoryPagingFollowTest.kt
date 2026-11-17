@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.dp
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.app.history.contract.*
import io.github.stream29.kodex.app.history.contract.item.HistoryItemViewModel
import io.github.stream29.kodex.app.ui.compose.ComposeHistoryViewState
import io.github.stream29.kodex.app.ui.compose.rememberComposeHistoryListRenderer
import io.github.stream29.kodex.cli.history.AgentHistoryContent
import io.github.stream29.kodex.cli.history.AgentHistorySource
import io.github.stream29.kodex.cli.history.createAgentHistoryViewModel
import io.github.stream29.kodex.openai.ContentItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

val offscreenHistoryPagingFollowTest by testSuite {
    for (input in listOf("return", "away", "none", "return-effect", "return-generation", "return-remount")) {
        test("last newer page completes after the wheel has stopped: $input") { offscreenTest {
            val stored = InMemoryKodexAgentStorage.empty()
            val body = (1..80).joinToString("\n") { "Delayed page line $it" }
            stored.commit(StableUserMessage(listOf(ContentItem.InputText(body))))
            val owner = CoroutineScope(currentCoroutineContext() + Job(currentCoroutineContext()[Job]))
            val model = createAgentHistoryViewModel(
                AgentHistorySource(stored, MutableStateFlow(1), MutableStateFlow(KodexAgentStateValue.UserMessage)),
                owner, MutableStateFlow(false),
            )
            try {
                withTimeout(5_000) { while (model.loadState.value != AgentHistoryLoadState.Ready) delay(1) }
                val latest = model.historyItems.value
                val loading = object : HistoryItemWindow by latest { override val hasNewer = true }
                val displayedWindow = MutableStateFlow<HistoryItemWindow>(loading)
                val load = MutableStateFlow<AgentHistoryLoadState>(AgentHistoryLoadState.LoadingNewer)
                val scrollEffect = MutableStateFlow<HistoryScrollEffect?>(null)
                model.setFollowsLatest(latest, false)
                val displayed = object : AgentHistoryViewModel by model {
                    override val historyItems = displayedWindow
                    override val loadState = load
                    override val pendingScrollEffect = scrollEffect
                    override fun reportViewport(window: HistoryItemWindow, visibleItems: List<HistoryItemViewModel>) = Unit
                    override fun setFollowsLatest(window: HistoryItemWindow, followsLatest: Boolean) {
                        assertSame(displayedWindow.value, window)
                        if (!followsLatest || !window.hasNewer) model.setFollowsLatest(latest, followsLatest)
                    }
                    override fun acknowledgeScrollEffect(effect: HistoryScrollEffect) {
                        scrollEffect.compareAndSet(effect, null)
                    }
                }
                var mounted by mutableStateOf(true)
                val scene = OffscreenScene.create(width = 480, height = 600) {
                    Box(Modifier.height(300.dp)) {
                        if (mounted) AgentHistoryContent(displayed, rememberComposeHistoryListRenderer(remember { ComposeHistoryViewState() }))
                        else Text("History unmounted")
                    }
                }
                try {
                    scene.settle { scene.hasText(body) }
                    fun wheel(y: Float) = scene.scene.sendPointerEvent(
                        PointerEventType.Scroll, Offset(200f, 100f), scrollDelta = Offset(0f, y),
                    )
                    if (input != "none") {
                        wheel(-180f)
                        scene.settle { !model.followsLatest.value }
                        if (input.startsWith("return")) {
                            wheel(5000f)
                            scene.settle { !model.followsLatest.value }
                            assertEquals(300f, scene.text(body).let { it.positionInRoot.y + it.size.height }, 1f)
                        }
                    }
                    // Complete a different immutable window only after input and its animation have settled.
                    repeat(30) { scene.advance(); delay(1) }
                    assertFalse(model.followsLatest.value, "Newer storage still exists before completion")
                    if (input == "return-effect") {
                        scrollEffect.value = HistoryScrollEffect(latest.generation, HistoryScrollTarget.Item(latest.peek(0)))
                        scene.settle { scrollEffect.value == null }
                    }
                    if (input == "return-remount") {
                        mounted = false
                        scene.settle { scene.hasText("History unmounted") }
                    }
                    displayedWindow.value = if (input == "return-generation") {
                        object : HistoryItemWindow by latest { override val generation = latest.generation + 1 }
                    } else latest
                    load.value = AgentHistoryLoadState.Ready
                    mounted = true
                    scene.settle { scene.hasText(body) }
                    assertEquals(input == "return", model.followsLatest.value,
                        "Only a genuine return may settle follow when the final page arrives without another wheel event")
                } finally { scene.close() }
            } finally {
                model.close()
                owner.coroutineContext.job.cancelAndJoin()
            }
        } }
    }
}
