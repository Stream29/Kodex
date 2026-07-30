package io.github.stream29.codex.lite.cli.history

import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Box
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableCommandExecutionAction
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableCommandExecutionResult
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableCommandExecutionToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableMcpToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableRequestUserInputResult
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableTextToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingFunctionToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingPatchToolEvent
import io.github.stream29.codex.lite.openai.AgentMessageInputContent
import io.github.stream29.codex.lite.openai.CallToolResult
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.PlanItemArg
import io.github.stream29.codex.lite.openai.StepStatus
import io.github.stream29.codex.lite.openai.UpdatePlanArgs
import io.github.stream29.codex.lite.openai.ReasoningItemContent
import io.github.stream29.codex.lite.openai.ReasoningItemReasoningSummary
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.tool.requestuserinput.RequestUserInputAnswer
import io.github.stream29.codex.lite.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.codex.lite.tool.requestuserinput.RequestUserInputQuestion
import io.github.stream29.codex.lite.tool.requestuserinput.RequestUserInputResponse
import io.github.stream29.codex.lite.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.codex.lite.tool.unifiedexec.UnifiedExecOutput
import io.github.stream29.codex.lite.tool.unifiedexec.UnifiedExecProcessSession
import io.github.stream29.codex.lite.tool.unifiedexec.WriteStdinArguments
import io.github.stream29.codex.lite.utils.applypatch.Patch
import io.github.stream29.codex.lite.utils.applypatch.UpdateFileChunk
import io.github.stream29.codex.lite.utils.applypatch.UpdateFileHunk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CleanEventViewTest {
    @Test
    fun stableMessagesRenderTheirOwnCleanModelContent() = runTest {
        runMosaicTest {
            assertEquals(
                "Assistant\nhello",
                setContentAndSnapshot {
                    Box(Modifier.width(40)) {
                        StableCleanEvent.AssistantMessage(
                            content = listOf(ContentItem.OutputText("hello")),
                        ).render()
                    }
                },
            )

            assertEquals(
                "root → child\ndelivered",
                setContentAndSnapshot {
                    Box(Modifier.width(40)) {
                        StableCleanEvent.AgentMessage(
                            author = "root",
                            recipient = "child",
                            content = listOf(AgentMessageInputContent.InputText("delivered")),
                        ).render()
                    }
                },
            )
        }
    }

    @Test
    fun unknownToolUsesItsRawNameAndDefersPayloadDetails() = runTest {
        val event = StableTextToolEvent(
            callId = "call",
            name = "demo",
            arguments = JsonObject(emptyMap()),
            result = "done",
            success = true,
        )

        runMosaicTest {
            val collapsed = setContentAndSnapshot {
                Box(Modifier.width(40)) { event.render() }
            }
            assertEquals("> demo · succeeded", collapsed)

            val expanded = clickFirstRow()
            assertTrue("v demo · succeeded" in expanded)
            assertTrue("Tool: demo" in expanded)
            assertTrue("> Arguments" in expanded)
            assertTrue("> Result" in expanded)
            assertFalse("Arguments: {}" in expanded)
            assertFalse("Result: done" in expanded)

            val arguments = clickRow(2)
            assertTrue("v Arguments" in arguments)
            assertTrue("Arguments: {}" in arguments)
        }
    }

    @Test
    fun commandToolSummarizesItsCommandAndDefersTheFunctionName() = runTest {
        val event = StableCommandExecutionToolEvent(
            callId = "command",
            action = StableCommandExecutionAction.ExecCommand(
                ExecCommandArguments(command = "pwd"),
            ),
            result = StableCommandExecutionResult.Output(
                UnifiedExecOutput(
                    chunkId = "chunk",
                    wallTimeSeconds = 0.1,
                    exitCode = 0,
                    originalTokenCount = 1,
                    output = "/workspace",
                ),
            ),
        )

        runMosaicTest {
            val collapsed = setContentAndSnapshot {
                Box(Modifier.width(60)) { event.render() }
            }
            assertEquals("> Run command: pwd · succeeded", collapsed)
            assertFalse("exec_command" in collapsed)

            val expanded = clickFirstRow()
            assertTrue("Tool: exec_command" in expanded)
            assertTrue("> Arguments" in expanded)
            assertFalse("Command: pwd" in expanded)

            val arguments = clickRow(2)
            assertTrue("Command: pwd" in arguments)
        }
    }

    @Test
    fun runningCommandReflectsItsLiveProcessCompletion() = runTest {
        val session = TestUnifiedExecProcessSession(
            sessionId = 7,
            arguments = ExecCommandArguments(command = "sleep 5"),
        )
        val event = StableCommandExecutionToolEvent(
            callId = "command",
            action = StableCommandExecutionAction.ExecCommand(session.arguments),
            result = StableCommandExecutionResult.Output(
                UnifiedExecOutput(
                    chunkId = "chunk",
                    wallTimeSeconds = 0.1,
                    sessionId = session.sessionId,
                    originalTokenCount = 0,
                    output = "",
                ),
            ),
        )

        runMosaicTest {
            assertEquals(
                "> Run command: sleep 5 · running",
                setContentAndSnapshot {
                    Box(Modifier.width(60)) { event.renderCommandExecution(session) }
                },
            )

            session.completed.value = true
            assertEquals("> Run command: sleep 5 · finished", awaitSnapshot())

            val expanded = clickFirstRow()
            assertTrue("> Process" in expanded)
            assertFalse("Process: finished; final output is ready" in expanded)

            val process = clickRow(3)
            assertTrue("Process: finished; final output is ready" in process)
        }
    }

    @Test
    fun commandWithoutAnObservableSessionDoesNotClaimToBeRunning() = runTest {
        val event = StableCommandExecutionToolEvent(
            callId = "command",
            action = StableCommandExecutionAction.ExecCommand(
                ExecCommandArguments(command = "sleep 5"),
            ),
            result = StableCommandExecutionResult.Output(
                UnifiedExecOutput(
                    chunkId = "chunk",
                    wallTimeSeconds = 0.1,
                    sessionId = 7,
                    originalTokenCount = 0,
                    output = "",
                ),
            ),
        )

        runMosaicTest {
            assertEquals(
                "> Run command: sleep 5 · finished",
                setContentAndSnapshot {
                    Box(Modifier.width(60)) { event.renderCommandExecution(session = null) }
                },
            )
        }
    }

    @Test
    fun writeStdinNamesTheCommandBehindItsActiveSession() = runTest {
        val session = TestUnifiedExecProcessSession(
            sessionId = 7,
            arguments = ExecCommandArguments(command = "tail -f build.log"),
        )
        val event = StableCommandExecutionToolEvent(
            callId = "write",
            action = StableCommandExecutionAction.WriteStdin(
                WriteStdinArguments(sessionId = session.sessionId),
            ),
            result = StableCommandExecutionResult.Output(
                UnifiedExecOutput(
                    chunkId = "chunk",
                    wallTimeSeconds = 0.1,
                    sessionId = session.sessionId,
                    originalTokenCount = 0,
                    output = "",
                ),
            ),
        )

        runMosaicTest {
            val collapsed = setContentAndSnapshot {
                Box(Modifier.width(60)) { event.renderCommandExecution(session) }
            }
            assertEquals("> Read output: tail -f build.log · running", collapsed)
            assertFalse("write_stdin" in collapsed)

            val expanded = clickFirstRow()
            assertTrue("Tool: write_stdin" in expanded)
            assertTrue("> Arguments" in expanded)
            assertFalse("Command: tail -f build.log" in expanded)

            val arguments = clickRow(2)
            assertTrue("Command: tail -f build.log" in arguments)
        }
    }

    @Test
    fun planUsesTheEventArgumentsInsteadOfSettings() = runTest {
        val event = io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StablePlanUpdate(
            callId = "plan",
            arguments = UpdatePlanArgs(
                explanation = "Current plan",
                plan = listOf(PlanItemArg("Implement renderer", StepStatus.InProgress)),
            ),
        )

        runMosaicTest {
            setContentAndSnapshot {
                Box(Modifier.width(48)) { event.render() }
            }
            val expanded = clickFirstRow()
            assertTrue("> Arguments" in expanded)
            assertFalse("Explanation: Current plan" in expanded)

            val arguments = clickRow(2)
            assertTrue("Explanation: Current plan" in arguments)
            assertTrue("Plan: [>] Implement renderer" in arguments)
        }
    }

    @Test
    fun requestUserInputRedactsSecretAnswers() = runTest {
        val arguments = RequestUserInputArgs(
            questions = listOf(
                RequestUserInputQuestion(
                    id = "password",
                    header = "Password",
                    question = "Enter password",
                    isSecret = true,
                ),
            ),
        )
        val event = io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableRequestUserInputToolEvent(
            callId = "input",
            arguments = arguments,
            result = StableRequestUserInputResult.Answered(
                RequestUserInputResponse(
                    answers = mapOf("password" to RequestUserInputAnswer(listOf("correct horse"))),
                ),
            ),
        )

        runMosaicTest {
            setContentAndSnapshot {
                Box(Modifier.width(60)) { event.render() }
            }
            val expanded = clickFirstRow()
            assertTrue("> Result" in expanded)
            assertFalse("Answer Password: [hidden]" in expanded)

            val result = clickRow(3)
            assertTrue("Answer Password: [hidden]" in result)
            assertFalse("correct horse" in result)
        }
    }

    @Test
    fun reasoningUsesOnlyTheDisplaySummary() = runTest {
        val event = StableCleanEvent.Reasoning(
            ResponseItem.Reasoning(
                summary = listOf(ReasoningItemReasoningSummary.SummaryText("short summary")),
                content = listOf(ReasoningItemContent.ReasoningText("private full reasoning")),
            ),
        )

        runMosaicTest {
            setContentAndSnapshot {
                Box(Modifier.width(60)) { event.render() }
            }
            val expanded = clickFirstRow()
            assertTrue("short summary" in expanded)
            assertFalse("private full reasoning" in expanded)
        }
    }

    @Test
    fun mcpImageContentDoesNotDumpInlineData() = runTest {
        val event = StableMcpToolEvent(
            callId = "mcp",
            name = "inspect",
            namespace = "mcp",
            arguments = JsonObject(emptyMap()),
            result = CallToolResult(
                content = listOf(
                    JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("image"),
                            "data" to JsonPrimitive("base64-image-data"),
                        ),
                    ),
                ),
            ),
        )

        runMosaicTest {
            val collapsed = setContentAndSnapshot {
                Box(Modifier.width(60)) { event.render() }
            }
            assertTrue(collapsed.startsWith("> mcp.inspect ·"))
            val expanded = clickFirstRow()
            assertTrue("> Result" in expanded)
            assertFalse("Image: [inline image, 17 characters]" in expanded)

            val result = clickRow(3)
            assertTrue("Image: [inline image, 17 characters]" in result)
            assertFalse("base64-image-data" in result)
        }
    }

    @Test
    fun unstableEventsRenderFromTheirOwnRoot() = runTest {
        val event = PendingFunctionToolEvent(
            callId = "call",
            name = "demo",
            arguments = JsonObject(emptyMap()),
        )

        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Box(Modifier.width(40)) { event.render() }
            }
            assertEquals("> demo · running", snapshot)
        }
    }

    @Test
    fun patchEventsKeepTheDedicatedPatchRenderer() = runTest {
        val diff = Patch(
            patch = "",
            hunks = listOf(
                UpdateFileHunk(
                    path = "file.txt",
                    chunks = listOf(
                        UpdateFileChunk(
                            oldLines = listOf("old"),
                            newLines = listOf("new"),
                        ),
                    ),
                ),
            ),
        )
        val event = PendingPatchToolEvent(callId = "patch", diff = diff)

        runMosaicTest {
            val collapsed = setContentAndSnapshot {
                Box(Modifier.width(40)) { event.render() }
            }
            assertEquals("> Editing 1 file · running", collapsed)
            assertFalse("apply_patch" in collapsed)

            val expanded = clickFirstRow()
            assertTrue("Tool: apply_patch" in expanded)
            assertTrue("> Changes" in expanded)
            assertFalse("M file.txt" in expanded)

            val changes = clickRow(2)
            assertTrue("M file.txt" in changes)
            assertTrue("- old" in changes)
            assertTrue("+ new" in changes)
        }
    }

}

private class TestUnifiedExecProcessSession(
    override val sessionId: Int,
    override val arguments: ExecCommandArguments,
) : UnifiedExecProcessSession {
    override val completed: MutableStateFlow<Boolean> = MutableStateFlow(false)
}

private suspend fun TestMosaic<String>.clickFirstRow(): String {
    return clickRow(0)
}

private suspend fun TestMosaic<String>.clickRow(row: Int): String {
    sendMouseEvent(MouseEvent(0, row, MouseEvent.Type.Press, MouseEvent.Button.Left))
    awaitSnapshot()
    sendMouseEvent(MouseEvent(0, row, MouseEvent.Type.Release, MouseEvent.Button.Left))
    return awaitSnapshot()
}
