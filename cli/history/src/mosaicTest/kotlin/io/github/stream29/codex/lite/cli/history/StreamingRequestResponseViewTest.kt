package io.github.stream29.codex.lite.cli.history

import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Box
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentStateValue
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponseItemId
import io.github.stream29.codex.lite.openai.ReasoningItemReasoningSummary
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamingRequestResponseViewTest {
    @Test
    fun messageReplaysTextAndContinuesIncrementally() = runTest {
        val events = replayingEvents(
            ResponsesStreamEvent.OutputItemAdded(
                outputIndex = 0,
                item = ResponseItem.Message(
                    id = ResponseItemId("message"),
                    role = MessageRole.Assistant,
                    content = emptyList(),
                ),
            ),
            ResponsesStreamEvent.OutputTextDelta(
                itemId = "message",
                outputIndex = 0,
                contentIndex = 0,
                delta = "hello",
            ),
        )

        runMosaicTest {
            setContentAndSnapshot {
                Box(Modifier.width(40)) {
                    CodexAgentStateValue.RequestResponse.Message(events).renderStreamingTail()
                }
            }
            assertEquals("Assistant · streaming\nhello", awaitSnapshot())

            events.emit(
                ResponsesStreamEvent.OutputTextDelta(
                    itemId = "message",
                    outputIndex = 0,
                    contentIndex = 0,
                    delta = " world",
                ),
            )
            assertEquals("Assistant · streaming\nhello world", awaitSnapshot())
        }
    }

    @Test
    fun messageItemPrefixUsesContentIndexWhenDeltaArrives() = runTest {
        val events = replayingEvents(
            ResponsesStreamEvent.OutputItemAdded(
                outputIndex = 0,
                item = ResponseItem.Message(
                    id = ResponseItemId("message"),
                    role = MessageRole.Assistant,
                    content = listOf(
                        ContentItem.OutputText("first"),
                        ContentItem.OutputText("second"),
                    ),
                ),
            ),
            ResponsesStreamEvent.OutputTextDelta(
                itemId = "message",
                outputIndex = 0,
                contentIndex = 1,
                delta = " part",
            ),
        )

        runMosaicTest {
            setContentAndSnapshot {
                Box(Modifier.width(40)) {
                    CodexAgentStateValue.RequestResponse.Message(events).renderStreamingTail()
                }
            }
            assertEquals(
                "Assistant · streaming\nfirst\nsecond part",
                awaitSnapshot(),
            )
        }
    }

    @Test
    fun reasoningShowsOnlySummaryReplay() = runTest {
        val events = replayingEvents(
            ResponsesStreamEvent.ReasoningSummaryTextDelta(
                itemId = "reasoning",
                outputIndex = 0,
                summaryIndex = 0,
                delta = "brief summary",
            ),
            ResponsesStreamEvent.ReasoningTextDelta(
                itemId = "reasoning",
                outputIndex = 0,
                contentIndex = 0,
                delta = "private reasoning",
            ),
            ResponsesStreamEvent.Other(JsonPrimitive("opaque provider data")),
        )

        runMosaicTest {
            setContentAndSnapshot {
                Box(Modifier.width(40)) {
                    CodexAgentStateValue.RequestResponse.Reasoning(events).renderStreamingTail()
                }
            }
            val snapshot = awaitSnapshot()
            assertTrue("Thinking · streaming" in snapshot)
            assertTrue("brief summary" in snapshot)
            assertFalse("private reasoning" in snapshot)
            assertFalse("opaque provider data" in snapshot)
        }
    }

    @Test
    fun reasoningItemPrefixUsesSummaryIndexWhenDeltaArrives() = runTest {
        val events = replayingEvents(
            ResponsesStreamEvent.OutputItemAdded(
                outputIndex = 0,
                item = ResponseItem.Reasoning(
                    id = ResponseItemId("reasoning"),
                    summary = listOf(
                        ReasoningItemReasoningSummary.SummaryText("first"),
                        ReasoningItemReasoningSummary.SummaryText("second"),
                    ),
                ),
            ),
            ResponsesStreamEvent.ReasoningSummaryTextDelta(
                itemId = "reasoning",
                outputIndex = 0,
                summaryIndex = 1,
                delta = " part",
            ),
        )

        runMosaicTest {
            setContentAndSnapshot {
                Box(Modifier.width(40)) {
                    CodexAgentStateValue.RequestResponse.Reasoning(events).renderStreamingTail()
                }
            }
            assertEquals(
                "Thinking · streaming\nfirst\nsecond part",
                awaitSnapshot(),
            )
        }
    }

    @Test
    fun messageNeverRendersInlineImageData() = runTest {
        val events = replayingEvents(
            ResponsesStreamEvent.OutputItemAdded(
                outputIndex = 0,
                item = ResponseItem.Message(
                    id = ResponseItemId("image"),
                    role = MessageRole.Assistant,
                    content = listOf(ContentItem.InputImage("data:image/png;base64,private-image-data")),
                ),
            ),
        )

        runMosaicTest {
            setContentAndSnapshot {
                Box(Modifier.width(40)) {
                    CodexAgentStateValue.RequestResponse.Message(events).renderStreamingTail()
                }
            }
            val snapshot = awaitSnapshot()
            assertTrue("[image]" in snapshot)
            assertFalse("private-image-data" in snapshot)
        }
    }

    @Test
    fun functionCallMergesItsArgumentsAndInputDeltas() = runTest {
        val events = replayingEvents(
            ResponsesStreamEvent.OutputItemAdded(
                outputIndex = 0,
                item = ResponseItem.FunctionCall(
                    id = ResponseItemId("tool"),
                    name = "run",
                    namespace = "shell",
                    arguments = "{",
                    callId = "call",
                ),
            ),
            ResponsesStreamEvent.ToolCallInputDelta(
                itemId = "tool",
                callId = "call",
                delta = "\"command\":\"pwd\"",
            ),
            ResponsesStreamEvent.ToolCallInputDelta(
                callId = "call",
                delta = ",\"tty\":true}",
            ),
        )

        runMosaicTest {
            setContentAndSnapshot {
                Box(Modifier.width(60)) {
                    CodexAgentStateValue.RequestResponse.ToolCall(events).renderStreamingTail()
                }
            }
            val collapsed = awaitSnapshot()
            assertEquals("> Run a command", collapsed)
            assertFalse("shell.run" in collapsed)

            val expanded = clickRow()
            assertTrue("Tool: shell.run" in expanded)
            assertTrue("> Input" in expanded)
            assertFalse("Input: {\"command\":\"pwd\",\"tty\":true}" in expanded)

            val input = clickRow(y = 2)
            assertTrue("Input: {\"command\":\"pwd\",\"tty\":true}" in input)
        }
    }

    @Test
    fun customToolCallMergesItsInputAndDeltas() = runTest {
        val events = replayingEvents(
            ResponsesStreamEvent.OutputItemAdded(
                outputIndex = 0,
                item = ResponseItem.CustomToolCall(
                    id = ResponseItemId("custom-tool"),
                    status = "in_progress",
                    callId = "custom-call",
                    name = "apply_patch",
                    input = "*** Begin Patch",
                ),
            ),
            ResponsesStreamEvent.ToolCallInputDelta(
                itemId = "custom-tool",
                delta = " + tail",
            ),
        )

        runMosaicTest {
            setContentAndSnapshot {
                Box(Modifier.width(60)) {
                    CodexAgentStateValue.RequestResponse.ToolCall(events).renderStreamingTail()
                }
            }
            val collapsed = awaitSnapshot()
            assertEquals("> apply_patch", collapsed)
        }
    }

    @Test
    fun startedRequestHasAStandaloneTail() = runTest {
        runMosaicTest {
            assertEquals(
                "Starting response…",
                setContentAndSnapshot {
                    Box(Modifier.width(40)) {
                        CodexAgentStateValue.RequestResponse.Started.renderStreamingTail()
                    }
                },
            )
        }
    }
}

private fun replayingEvents(
    vararg initial: ResponsesStreamEvent,
): MutableSharedFlow<ResponsesStreamEvent> = MutableSharedFlow<ResponsesStreamEvent>(replay = Int.MAX_VALUE).also { events ->
    initial.forEach { event -> check(events.tryEmit(event)) }
}

private suspend fun TestMosaic<String>.clickRow(y: Int = 0): String {
    sendMouseEvent(MouseEvent(0, y, MouseEvent.Type.Press, MouseEvent.Button.Left))
    awaitSnapshot()
    sendMouseEvent(MouseEvent(0, y, MouseEvent.Type.Release, MouseEvent.Button.Left))
    return awaitSnapshot()
}
