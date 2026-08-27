package io.github.stream29.kodex.cli.history

import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.testing.SnapshotStrategy
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTextToolEvent
import io.github.stream29.kodex.app.agent.contract.AgentShellSession
import io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry
import io.github.stream29.kodex.app.history.contract.item.CommandExecutionHistoryAction
import io.github.stream29.kodex.app.history.contract.item.CommandExecutionHistoryResult
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemState
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.ReasoningHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.RequestUserInputHistoryItemState
import io.github.stream29.kodex.app.history.contract.item.RequestUserInputHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.ToolHistoryItemHeader
import io.github.stream29.kodex.app.history.contract.item.ToolHistoryItemState
import io.github.stream29.kodex.app.history.contract.item.ToolHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.WorkGroupChildHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.WorkGroupHistoryItemState
import io.github.stream29.kodex.app.history.contract.item.WorkGroupHistoryItemViewModel
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputAnswer
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestionOption
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration

private val ansiSnapshots = SnapshotStrategy { mosaic ->
    mosaic.draw().render(AnsiLevel.TRUECOLOR, supportsKittyUnderlines = false)
}

val agentHistoryEntryInteractionTest by testSuite {
    test("renders a loaded message and preserves the row context action") {
        var callbackCount = 0
        var capturedIndex: Int? = null
        val item = FakeMessage(
            index = 17,
            initialState = MessageHistoryItemState.Ready(
                event = StableCleanEvent.AssistantMessage(
                    listOf(ContentItem.OutputText("first line\nsecond line")),
                ),
                elapsed = Duration.ZERO,
            ),
        )

        runMosaicTest(snapshotStrategy = ansiSnapshots) {
            var rendered = setContentAndSnapshot {
                Column(modifier = Modifier.width(40)) {
                    StoredHistoryEntry(
                        item = item,
                        generation = 4,
                        shellSessions = EmptyHistoryShellSessions,
                        onOpenContextMenu = { _, index, _, _ ->
                            capturedIndex = index
                            callbackCount++
                        },
                    )
                }
            }
            if ("first line" !in rendered) rendered = awaitSnapshot()
            assertTrue("first line" in rendered)

            sendMouseEvent(
                com.jakewharton.mosaic.terminal.MouseEvent(
                    6,
                    0,
                    com.jakewharton.mosaic.terminal.MouseEvent.Type.Press,
                    com.jakewharton.mosaic.terminal.MouseEvent.Button.Right,
                ),
            )
            sendMouseEvent(
                com.jakewharton.mosaic.terminal.MouseEvent(
                    6,
                    0,
                    com.jakewharton.mosaic.terminal.MouseEvent.Type.Release,
                ),
            )
            awaitSnapshot()

            assertEquals(1, callbackCount)
            assertEquals(17, capturedIndex)
        }
    }

    test("renders a tool header and delegates expansion to its item state") {
        val item = FakeTool(index = 23)

        runMosaicTest {
            var collapsed = setContentAndSnapshot {
                Column(modifier = Modifier.width(40)) {
                    StoredHistoryEntry(
                        item = item,
                        generation = 8,
                        shellSessions = EmptyHistoryShellSessions,
                        onOpenContextMenu = null,
                    )
                }
            }
            if ("> demo" !in collapsed) collapsed = awaitSnapshot()
            assertTrue("> demo" in collapsed)

            sendMouseEvent(
                com.jakewharton.mosaic.terminal.MouseEvent(
                    0,
                    0,
                    com.jakewharton.mosaic.terminal.MouseEvent.Type.Press,
                    com.jakewharton.mosaic.terminal.MouseEvent.Button.Left,
                ),
            )
            sendMouseEvent(
                com.jakewharton.mosaic.terminal.MouseEvent(
                    0,
                    0,
                    com.jakewharton.mosaic.terminal.MouseEvent.Type.Release,
                ),
            )
            awaitSnapshot()

            assertIs<ToolHistoryItemState.Expanded>(item.state.value)
        }
    }

    test("renders a work-group header and its nested children only when expanded") {
        val group = FakeWorkGroup(
            children = listOf(
                FakeTool(index = 29),
                ReasoningHistoryItemViewModel(index = 23, elapsed = Duration.ZERO),
            ),
        )

        runMosaicTest {
            var collapsed = setContentAndSnapshot {
                Column(modifier = Modifier.width(40)) {
                    StoredHistoryWorkGroup(
                        group = group,
                        generation = 12,
                        shellSessions = EmptyHistoryShellSessions,
                        onOpenContextMenu = null,
                    )
                }
            }
            if ("> Take 2 actions" !in collapsed) collapsed = awaitSnapshot()
            assertTrue("> Take 2 actions" in collapsed)

            sendMouseEvent(
                com.jakewharton.mosaic.terminal.MouseEvent(
                    0,
                    0,
                    com.jakewharton.mosaic.terminal.MouseEvent.Type.Press,
                    com.jakewharton.mosaic.terminal.MouseEvent.Button.Left,
                ),
            )
            sendMouseEvent(
                com.jakewharton.mosaic.terminal.MouseEvent(
                    0,
                    0,
                    com.jakewharton.mosaic.terminal.MouseEvent.Type.Release,
                ),
            )
            var expanded = awaitSnapshot()
            if ("Think" !in expanded) expanded = awaitSnapshot()

            assertIs<WorkGroupHistoryItemState.Expanded>(group.state.value)
            assertTrue("Think" in expanded)
        }
    }

    test("renders completed user input as a read-only answered panel") {
        val item = FakeRequestUserInput(
            index = 31,
            event = StableRequestUserInputToolEvent(
                callId = "input",
                arguments = RequestUserInputArgs(
                    questions = listOf(
                        RequestUserInputQuestion(
                            id = "layout",
                            header = "Layout",
                            question = "Which layout should be used?",
                            options = listOf(
                                RequestUserInputQuestionOption("Compact", "Use less space"),
                                RequestUserInputQuestionOption("Detailed", "Show every field"),
                            ),
                        ),
                    ),
                ),
                result = StableRequestUserInputResult.Answered(
                    RequestUserInputResponse(
                        answers = mapOf(
                            "layout" to RequestUserInputAnswer(listOf("Compact")),
                        ),
                    ),
                ),
            ),
        )

        runMosaicTest {
            val rendered = setContentAndSnapshot {
                Column(modifier = Modifier.width(60)) {
                    StoredHistoryEntry(
                        item = item,
                        generation = 9,
                        shellSessions = EmptyHistoryShellSessions,
                        onOpenContextMenu = null,
                    )
                }
            }

            assertEquals(
                listOf(
                    "Layout: Which layout should be used? +0s",
                    "[● Compact]",
                    "  Use less space",
                ),
                rendered.lines(),
            )
            assertFalse("Detailed" in rendered)
            assertFalse("Show every field" in rendered)
            assertFalse("request_user_input" in rendered)
            assertFalse("Arguments" in rendered)
            assertFalse("Result" in rendered)
        }
    }

    test("collapsed command header follows its live terminal session") {
        val session = FakeShellSession(
            sessionId = 7,
            arguments = ExecCommandArguments(command = "tail -f build.log"),
        )
        val sessions = FakeShellSessions(mapOf(session.sessionId to session))
        val item = FakeTool(
            index = 33,
            header = ToolHistoryItemHeader.CommandExecution(
                action = CommandExecutionHistoryAction.Wait(session.sessionId),
                result = CommandExecutionHistoryResult.Output(
                    exitCode = null,
                    sessionId = session.sessionId,
                ),
                elapsed = Duration.ZERO,
            ),
        )

        runMosaicTest(snapshotStrategy = ansiSnapshots) {
            val running = setContentAndSnapshot {
                Column(modifier = Modifier.width(60)) {
                    StoredHistoryEntry(
                        item = item,
                        generation = 10,
                        shellSessions = sessions,
                        onOpenContextMenu = null,
                    )
                }
            }
            assertTrue("> Wait for tail -f build.log" in running, running)
            assertTrue("38;2;0;255;0" in running, running)

            session.completed.value = true
            val completed = awaitSnapshot()
            assertTrue("> Wait for tail -f build.log" in completed, completed)
            assertTrue("38;2;0;255;0" !in completed, completed)
        }
    }
}

private class FakeMessage(
    override val index: Int,
    initialState: MessageHistoryItemState,
) : MessageHistoryItemViewModel {
    override val state = MutableStateFlow(initialState)
}

private class FakeTool(
    override val index: Int,
    header: ToolHistoryItemHeader = ToolHistoryItemHeader.Summary(
        summary = "demo",
        status = "completed",
        elapsed = Duration.ZERO,
    ),
) : ToolHistoryItemViewModel {
    private val event = StableTextToolEvent(
        callId = "call-$index",
        name = "demo",
        arguments = JsonObject(emptyMap()),
        result = "done",
        success = true,
    )

    override val state = MutableStateFlow<ToolHistoryItemState>(
        ToolHistoryItemState.Collapsed(header),
    )

    override fun expand() {
        val collapsed = state.value as? ToolHistoryItemState.Collapsed ?: return
        state.value = ToolHistoryItemState.Expanded(collapsed.header, event)
    }

    override fun collapse() {
        val current = state.value
        state.value = when (current) {
            is ToolHistoryItemState.Expanded -> ToolHistoryItemState.Collapsed(current.header)
            is ToolHistoryItemState.Expanding -> ToolHistoryItemState.Collapsed(current.header)
            else -> current
        }
    }
}

private class FakeRequestUserInput(
    override val index: Int,
    event: StableRequestUserInputToolEvent,
) : RequestUserInputHistoryItemViewModel {
    override val state = MutableStateFlow<RequestUserInputHistoryItemState>(
        RequestUserInputHistoryItemState.Ready(event, Duration.ZERO),
    )
}

private class FakeWorkGroup(
    override val indexRange: IntRange = 23..29,
    private val children: List<WorkGroupChildHistoryItemViewModel>,
) : WorkGroupHistoryItemViewModel {
    override val itemCount: Int = children.size
    override val state = MutableStateFlow<WorkGroupHistoryItemState>(
        WorkGroupHistoryItemState.Collapsed(Duration.ZERO),
    )

    override fun expand() {
        state.value = WorkGroupHistoryItemState.Expanded(children, Duration.ZERO)
    }

    override fun collapse() {
        state.value = WorkGroupHistoryItemState.Collapsed(Duration.ZERO)
    }
}

private object EmptyHistoryShellSessions : AgentShellSessionRegistry {
    override val activeSessions: StateFlow<Map<Int, AgentShellSession>> =
        MutableStateFlow(emptyMap())
}

private class FakeShellSessions(
    sessions: Map<Int, AgentShellSession>,
) : AgentShellSessionRegistry {
    override val activeSessions: StateFlow<Map<Int, AgentShellSession>> =
        MutableStateFlow(sessions)
}

private class FakeShellSession(
    override val sessionId: Int,
    override val arguments: ExecCommandArguments,
) : AgentShellSession {
    override val completed = MutableStateFlow(false)

    override fun close() = Unit
}
