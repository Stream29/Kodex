package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.MosaicSnapshots
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Box
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionAction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableMcpToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTextToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingFunctionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPatchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPlanUpdate
import io.github.stream29.kodex.app.agent.contract.AgentShellSession
import io.github.stream29.kodex.openai.AgentMessageInputContent
import io.github.stream29.kodex.openai.CallToolResult
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.PlanItemArg
import io.github.stream29.kodex.openai.StepStatus
import io.github.stream29.kodex.openai.UpdatePlanArgs
import io.github.stream29.kodex.openai.ReasoningItemContent
import io.github.stream29.kodex.openai.ReasoningItemReasoningSummary
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputAnswer
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecOutput
import io.github.stream29.kodex.tool.unifiedexec.WriteStdinArguments
import io.github.stream29.kodex.utils.applypatch.Patch
import io.github.stream29.kodex.utils.applypatch.UpdateFileChunk
import io.github.stream29.kodex.utils.applypatch.UpdateFileHunk
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
            assertEquals("> demo", collapsed)

            val expanded = clickFirstRow()
            assertTrue("v demo" in expanded)
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
            assertEquals("> Run command: pwd", collapsed)
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
    fun commandToolSummaryEllipsizesToAvailableHistoryWidth() = runTest {
        val event = StableCommandExecutionToolEvent(
            callId = "command",
            action = StableCommandExecutionAction.ExecCommand(
                ExecCommandArguments(command = "A你B".repeat(30)),
            ),
            result = StableCommandExecutionResult.Output(
                UnifiedExecOutput(
                    chunkId = "chunk",
                    wallTimeSeconds = 0.1,
                    exitCode = 0,
                    originalTokenCount = 1,
                    output = "",
                ),
            ),
        )
        var width by mutableIntStateOf(140)

        runMosaicTest {
            val collapsed = setContentAndSnapshot {
                Box(Modifier.width(width)) { event.render() }
            }
            assertEquals("> Run command: ${"A你B".repeat(30)}", collapsed)

            width = 42
            assertEquals("> Run command: ${"A你B".repeat(6)}...", awaitSnapshot())
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
                "> Run command: sleep 5",
                setContentAndSnapshot {
                    Box(Modifier.width(60)) { event.renderCommandExecution(session) }
                },
            )

            session.completed.value = true
            assertEquals("> Run command: sleep 5", awaitSnapshot())

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
                "> Run command: sleep 5",
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
            assertEquals("> Read output: tail -f build.log", collapsed)
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
    fun planRendersAsAnInlineChecklist() = runTest {
        val event = io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate(
            callId = "plan",
            arguments = UpdatePlanArgs(
                explanation = "Current plan",
                plan = listOf(
                    PlanItemArg("Inspect the current renderer", StepStatus.Completed),
                    PlanItemArg("Implement the checklist", StepStatus.InProgress),
                    PlanItemArg("Verify the history view", StepStatus.Pending),
                ),
            ),
        )

        runMosaicTest {
            val rendered = setContentAndSnapshot {
                Box(Modifier.width(48)) { event.render() }
            }
            assertEquals(
                """
                • Updated Plan
                  └ Current plan
                    [x] Inspect the current renderer
                    [>] Implement the checklist
                    [ ] Verify the history view
                """.trimIndent(),
                rendered,
            )
            assertFalse("Plan:" in rendered)
            assertFalse("Arguments" in rendered)
            assertFalse("update_plan" in rendered)
        }
    }

    @Test
    fun pendingPlanRendersAsAnInlineChecklist() = runTest {
        val event = PendingPlanUpdate(
            callId = "plan",
            arguments = UpdatePlanArgs(
                plan = listOf(PlanItemArg("Implement the checklist", StepStatus.InProgress)),
            ),
        )

        runMosaicTest {
            assertEquals(
                "• Updating Plan\n  └ [>] Implement the checklist",
                setContentAndSnapshot {
                    Box(Modifier.width(48)) { event.render() }
                },
            )
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
        val event = io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputToolEvent(
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
            assertEquals("> mcp.inspect", collapsed)
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
            assertEquals("> demo", snapshot)
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
            assertEquals("> Editing 1 file", collapsed)
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

    @Test
    fun toolHeadersUseColorInsteadOfTextualStatus() = runTest {
        val running = PendingFunctionToolEvent(
            callId = "running",
            name = "demo",
            arguments = JsonObject(emptyMap()),
        )
        val succeeded = StableTextToolEvent(
            callId = "succeeded",
            name = "demo",
            arguments = JsonObject(emptyMap()),
            result = "done",
            success = true,
        )
        val failed = StableTextToolEvent(
            callId = "failed",
            name = "demo",
            arguments = JsonObject(emptyMap()),
            result = "error",
            success = false,
        )

        runMosaicTest(MosaicSnapshots) {
            val pending = setContentAndSnapshot {
                Box(Modifier.width(40)) { running.render() }
            }.draw().render(
                ansiLevel = AnsiLevel.TRUECOLOR,
                supportsKittyUnderlines = false,
            )
            assertTrue("38;2;0;255;0" in pending)
            assertFalse("running" in pending)

            val normal = setContentAndSnapshot {
                Box(Modifier.width(40)) { succeeded.render() }
            }.draw().render(
                ansiLevel = AnsiLevel.TRUECOLOR,
                supportsKittyUnderlines = false,
            )
            assertTrue("38;2;255;255;255" in normal)
            assertFalse("succeeded" in normal)

            val failure = setContentAndSnapshot {
                Box(Modifier.width(40)) { failed.render() }
            }.draw().render(
                ansiLevel = AnsiLevel.TRUECOLOR,
                supportsKittyUnderlines = false,
            )
            assertTrue("38;2;255;0;0" in failure)
            assertFalse("failed" in failure)
        }
    }

}

private class TestUnifiedExecProcessSession(
    override val sessionId: Int,
    override val arguments: ExecCommandArguments,
) : AgentShellSession {
    override val completed: MutableStateFlow<Boolean> = MutableStateFlow(false)

    override fun close() = Unit
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
