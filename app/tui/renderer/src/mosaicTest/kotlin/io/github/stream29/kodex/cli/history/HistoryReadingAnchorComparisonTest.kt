package io.github.stream29.kodex.cli.history

import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.app.history.contract.*
import io.github.stream29.kodex.openai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

val historyReadingAnchorComparisonTest by testSuite {
    test("Native keeps the same visible text line when a scrolled-away streaming row grows") {
        coroutineScope {
            val owner = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
            val model = createAgentHistoryViewModel(
                AgentHistorySource(InMemoryKodexAgentStorage.empty(), MutableStateFlow(0),
                    MutableStateFlow(KodexAgentStateValue.UserMessage)), owner, MutableStateFlow(false),
            )
            val events = MutableSharedFlow<ResponsesStreamEvent>(replay = 128)
            val text = (0 until 80).joinToString("\n", postfix = "\n") { "READING_${it.toString().padStart(3, '0')}" }
            events.tryEmit(ResponsesStreamEvent.OutputItemAdded(0, ResponseItem.Message(
                ResponseItemId("reading-anchor"), MessageRole.Assistant, listOf(ContentItem.OutputText(text)),
            )))
            val displayed = object : AgentHistoryViewModel by model {
                override val streamingItem = MutableStateFlow<HistoryStreamingItem?>(
                    HistoryStreamingItem.Output(HistoryStreamingKind.Message, events),
                )
            }
            val state = AgentHistoryViewState()
            try {
                runMosaicTest {
                    var output = setContentAndSnapshot {
                        io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                            Column(Modifier.width(40).height(12)) {
                                AgentHistoryContent(displayed, MosaicHistoryListRenderer(state, null))
                            }
                        }
                    }
                    withTimeout(5.seconds) {
                        while ("READING_079" !in output) output = awaitSnapshot()
                    }
                    repeat(3) {
                        sendMouseEvent(MouseEvent(1, 1, MouseEvent.Type.Press, MouseEvent.Button.WheelUp))
                        output = awaitSnapshot()
                    }
                    assertFalse(model.followsLatest.value)
                    val before = output.lines()
                    val anchorY = before.indices.first { it >= 2 && "READING_" in before[it] }
                    val marker = assertNotNull(Regex("READING_\\d+").find(before[anchorY])).value
                    val oldSize = state.listState.layoutInfo.visibleItemsInfo.single().size
                    val delta = (80 until 88).joinToString("\n", postfix = "\n") { "READING_$it" }
                    events.emit(ResponsesStreamEvent.OutputTextDelta("reading-anchor", 0, 0, delta))
                    withTimeout(5.seconds) {
                        // Pump the throttled shared streaming snapshot and its subsequent measure.
                        repeat(4) {
                            try { output = awaitSnapshot(200.milliseconds) }
                            catch (_: TimeoutCancellationException) { }
                        }
                    }
                    assertEquals(anchorY, output.lines().indexOfFirst { marker in it },
                        "The same actual terminal text must stay on the same visible row")
                    assertEquals(oldSize + 8, state.listState.layoutInfo.visibleItemsInfo.single().size,
                        "The compared Native frame must include the appended text")
                    assertFalse(model.followsLatest.value)
                }
            } finally {
                model.close()
                owner.coroutineContext.job.cancelAndJoin()
            }
        }
    }
}
