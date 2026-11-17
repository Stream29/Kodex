@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.app.history.contract.*
import io.github.stream29.kodex.app.ui.compose.ComposeHistoryViewState
import io.github.stream29.kodex.app.ui.compose.rememberComposeHistoryListRenderer
import io.github.stream29.kodex.cli.history.AgentHistoryContent
import io.github.stream29.kodex.cli.history.AgentHistorySource
import io.github.stream29.kodex.cli.history.createAgentHistoryViewModel
import io.github.stream29.kodex.openai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

val offscreenHistoryReadingAnchorTest by testSuite {
    test("the same visible text line stays in place when a scrolled-away streaming row grows") { offscreenTest {
        val owner = CoroutineScope(currentCoroutineContext() + Job(currentCoroutineContext()[Job]))
        val model = createAgentHistoryViewModel(
            AgentHistorySource(InMemoryKodexAgentStorage.empty(), MutableStateFlow(0),
                MutableStateFlow(KodexAgentStateValue.UserMessage)), owner, MutableStateFlow(false),
        )
        val events = MutableSharedFlow<ResponsesStreamEvent>(replay = 128)
        val text = (0 until 80).joinToString("\n\n") { "READING_${it.toString().padStart(3, '0')}" }
        events.tryEmit(ResponsesStreamEvent.OutputItemAdded(0, ResponseItem.Message(
            ResponseItemId("reading-anchor"), MessageRole.Assistant, listOf(ContentItem.OutputText(text)),
        )))
        val displayed = object : AgentHistoryViewModel by model {
            override val streamingItem = MutableStateFlow<HistoryStreamingItem?>(
                HistoryStreamingItem.Output(HistoryStreamingKind.Message, events),
            )
        }
        val scene = OffscreenScene.create(width = 480, height = 600) {
            Box(Modifier.height(300.dp)) {
                AgentHistoryContent(displayed, rememberComposeHistoryListRenderer(remember { ComposeHistoryViewState() }))
            }
        }
        fun layout(marker: String): TextLayoutResult {
            val results = mutableListOf<TextLayoutResult>()
            assertTrue(assertNotNull(scene.text(marker).config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)(results))
            return results.single()
        }
        try {
            scene.settle { scene.hasText("READING_079") }
            scene.scene.sendPointerEvent(PointerEventType.Scroll, Offset(200f, 100f), scrollDelta = Offset(0f, -180f))
            scene.settle { !model.followsLatest.value }
            val marker = (0 until 80).map { "READING_${it.toString().padStart(3, '0')}" }
                .first { scene.text(it).positionInRoot.y in 40f..200f }
            val before = layout(marker)
            assertEquals(1, before.lineCount)
            val anchorY = scene.text(marker).positionInRoot.y + before.getLineTop(0)
            val delta = (80 until 88).joinToString("") { "\n\nREADING_$it" }
            events.emit(ResponsesStreamEvent.OutputTextDelta("reading-anchor", 0, 0, delta))
            scene.settle { scene.hasText("READING_87") }
            val after = layout(marker)
            assertEquals(1, after.lineCount)
            val afterY = scene.text(marker).positionInRoot.y + after.getLineTop(0)
            assertEquals(anchorY, afterY, 1f, "Visible text $marker must stay at the same pixel, not merely remain away from Latest")
            assertFalse(model.followsLatest.value)
        } finally {
            scene.close()
            model.close()
            owner.coroutineContext.job.cancelAndJoin()
        }
    } }
}
