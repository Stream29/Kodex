package io.github.stream29.kodex.cli.history

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.InvalidToolInvocation
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAwait
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAwaitEventResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionAction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecution
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCustomTool
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableInvalidToolCall
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableJsonTool
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatch
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatchToolExecutionResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableReadMediaResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableReadMedia
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableServerToolSearch
import io.github.stream29.kodex.agentstorage.contract.KodexAgentStorage
import io.github.stream29.kodex.app.history.contract.item.CommandExecutionHistoryAction
import io.github.stream29.kodex.app.history.contract.item.CommandExecutionHistoryResult
import io.github.stream29.kodex.app.history.contract.item.PatchHistoryItemStatus
import io.github.stream29.kodex.app.history.contract.item.PatchHistoryItemTarget
import io.github.stream29.kodex.app.history.contract.item.ToolHistoryItemHeader
import io.github.stream29.kodex.app.history.contract.item.ToolHistoryItemState
import io.github.stream29.kodex.openai.FunctionCallOutputPayload
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.tool.awaitevent.AwaitEventArguments
import io.github.stream29.kodex.tool.awaitevent.AwaitEventInput
import io.github.stream29.kodex.tool.awaitevent.AwaitEventOutput
import io.github.stream29.kodex.tool.readmedia.MediaDetail
import io.github.stream29.kodex.tool.readmedia.ReadMediaToolArguments
import io.github.stream29.kodex.tool.readmedia.ReadMediaToolOutput
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecOutput
import io.github.stream29.kodex.utils.applypatch.Patch
import io.github.stream29.kodex.utils.applypatch.UpdateFileHunk
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

val historyItemHeaderFactoryTest by testSuite {
    test("readMediaHeaderUsesBoundedPathAndMapsFailureStatus") {
        val success = assertIs<ToolHistoryItemHeader.Summary>(
            StableReadMedia(
                callId = "media-1",
                arguments = ReadMediaToolArguments("/tmp/diagram.png"),
                result = StableReadMediaResult.Success(
                    ReadMediaToolOutput("data:image/png;base64,AAAA", MediaDetail.High),
                ),
            ).toHistoryHeader(1.milliseconds),
        )
        assertEquals("Read media: /tmp/diagram.png", success.summary)
        assertEquals("completed", success.status)

        val failure = assertIs<ToolHistoryItemHeader.Summary>(
            StableReadMedia(
                callId = "media-2",
                arguments = ReadMediaToolArguments("/tmp/missing.png"),
                result = StableReadMediaResult.Failure("image file does not exist"),
            ).toHistoryHeader(1.milliseconds),
        )
        assertEquals("Failed to read media: /tmp/missing.png", failure.summary)
        assertEquals("failed", failure.status)
    }

    test("migratedFunctionHeaderMapsSuccessAndFailureSummary") {
        fun header(success: Boolean?) = assertIs<ToolHistoryItemHeader.Summary>(
            StableJsonTool(
                callId = "mcp",
                name = "inspect",
                namespace = "filesystem",
                arguments = JsonObject(emptyMap()),
                result = JsonObject(emptyMap()),
                success = success,
            ).toHistoryHeader(1.milliseconds),
        )

        assertEquals("completed", header(success = true).status)
        assertEquals("filesystem.inspect", header(success = true).summary)
        assertEquals("completed", header(success = null).status)
        assertEquals("failed", header(success = false).status)
        assertEquals("Failed to run filesystem.inspect", header(success = false).summary)
    }

    test("command header retains only its own intent name and outcome discriminators") {
        val event = StableCommandExecution(
            callId = "command",
            action = StableCommandExecutionAction.ExecCommand(
                ExecCommandArguments(
                    command = "printf first\n" + "x".repeat(300),
                    name = "读取 测试日志 🧪",
                    yieldTimeMillis = 10_000,
                ),
            ),
            result = StableCommandExecutionResult.Output(
                UnifiedExecOutput(
                    chunkId = "chunk",
                    wallTimeSeconds = 1.0,
                    exitCode = 0,
                    sessionId = 7,
                    originalTokenCount = 10,
                    output = "large output that must not enter the header",
                ),
            ),
        )

        val header = assertIs<ToolHistoryItemHeader.CommandExecution>(
            event.toHistoryHeader(12.milliseconds),
        )
        val action = assertIs<CommandExecutionHistoryAction.Run>(header.action)
        assertEquals("读取 测试日志 🧪", action.intentName)
        assertFalse("printf" in header.toString())
        assertFalse("large output" in header.toString())
        assertEquals(
            CommandExecutionHistoryResult.Output(exitCode = 0, sessionId = 7),
            header.result,
        )
        assertEquals(12.milliseconds, header.elapsed)
    }

    test("await headers keep the recorded name for both outcomes and a generic title for invalid calls") {
        val elapsed = 12.milliseconds
        val arguments = AwaitEventArguments(events = listOf(AwaitEventInput.UserMessage), name = "等待用户回复 🧪")
        for ((result, status) in listOf(
            StableAwaitEventResult.Output(AwaitEventOutput(Instant.fromEpochMilliseconds(0))) to "completed",
            StableAwaitEventResult.Failure("fixture failure") to "failed",
        )) {
            val event = StableAwait("await", arguments = arguments, result = result)
            assertEquals(ToolHistoryItemHeader.Summary(arguments.name, status, elapsed), event.toHistoryHeader(elapsed))
        }
        for ((tool, title) in listOf("await_event" to "Wait for events", "exec_command" to "Run a command")) {
            val event = StableInvalidToolCall(
                "invalid",
                invocation = InvalidToolInvocation.Function(tool, arguments = "{BROKEN"),
                message = "fixture validation error",
            )
            assertEquals(ToolHistoryItemHeader.Summary(title, "failed", elapsed), event.toHistoryHeader(elapsed))
        }
    }

    test("materialized command disclosure does not read storage when projected or toggled") {
        coroutineScope {
            var reads = 0
            fun unexpectedRead(): Nothing {
                reads++
                error("A materialized command must not read storage")
            }
            val storage = object : KodexAgentStorage {
                override suspend fun latestIndex() = unexpectedRead()
                override suspend fun countCommitsIn(range: IntRange, types: Set<AgentCommitType>) = unexpectedRead()
                override suspend fun getFirstCommitsIn(range: IntRange, types: Set<AgentCommitType>, limit: Int) =
                    unexpectedRead()
                override suspend fun getLastCommitsIn(range: IntRange, types: Set<AgentCommitType>, limit: Int) =
                    unexpectedRead()
            }
            val context = HistoryItemLoadContext(
                AgentHistorySource(storage, MutableStateFlow(6), MutableStateFlow(KodexAgentStateValue.ToolCompleted)),
                this,
            ) { true }
            for (name in listOf("Run a command", "Inspect fixture output")) {
                val event = StableCommandExecution(
                    callId = "command",
                    action = StableCommandExecutionAction.ExecCommand(
                        ExecCommandArguments(command = "printf first\nprintf last", name = name, yieldTimeMillis = 100),
                    ),
                    result = StableCommandExecutionResult.Failure("fixture failure"),
                )
                val item = ToolHistoryItemViewModelImpl(
                    6,
                    event.toHistoryItemDescriptor(6, HistoryItemSource.Work, 1.milliseconds),
                    context,
                )
                val collapsed = assertIs<ToolHistoryItemState.Collapsed>(item.state.value)
                assertEquals(
                    CommandExecutionHistoryAction.Run(name),
                    assertIs<ToolHistoryItemHeader.CommandExecution>(collapsed.header).action,
                )
                item.expand()
                val expanded = assertIs<ToolHistoryItemState.Expanded>(item.state.value)
                assertSame(event, expanded.event)
                assertSame(collapsed.header, expanded.header)
                item.collapse()
                assertEquals(collapsed, item.state.value)
            }
            assertEquals(0, reads)
        }
    }

    test("serverToolSearchHeaderDoesNotContainBodyInputs") {
        val event = StableServerToolSearch(
            call = ResponseItem.ServerToolSearchCall(
                status = "completed",
                arguments = JsonObject(
                    mapOf(
                        "paths" to JsonArray(
                            listOf(JsonPrimitive("crm"), JsonPrimitive("billing")),
                        ),
                    ),
                ),
            ),
            output = ResponseItem.ServerToolSearchOutput(
                status = "completed",
                tools = emptyList(),
            ),
        )

        val header = assertIs<ToolHistoryItemHeader.Summary>(
            event.toHistoryHeader(3.milliseconds),
        )
        assertEquals("Search available tools", header.summary)
    }

    test("customWebHeaderIncludesTheSearchQuery") {
        val event = StableCustomTool(
            callId = "web",
            name = "run",
            namespace = "web",
            input = """{"search_query":[{"q":"Kotlin Duration"}]}""",
            result = FunctionCallOutputPayload.fromText("done"),
            success = true,
        )

        val header = assertIs<ToolHistoryItemHeader.Summary>(
            event.toHistoryHeader(5.milliseconds),
        )
        assertEquals("Search the web: Kotlin Duration", header.summary)
    }

    test("patchHeaderUsesBasenameForOneFileAndCountForMultipleFiles") {
        val single = StablePatch(
            callId = "single",
            diff = Patch(
                patch = "",
                hunks = listOf(UpdateFileHunk(path = "src/Main.kt", chunks = emptyList())),
            ),
            result = StablePatchToolExecutionResult.Failure("not applied"),
        ).toHistoryHeader(1.milliseconds)
        val multiple = StablePatch(
            callId = "multiple",
            diff = Patch(
                patch = "",
                hunks = listOf(
                    UpdateFileHunk(path = "src/Main.kt", chunks = emptyList()),
                    UpdateFileHunk(path = "test/MainTest.kt", chunks = emptyList()),
                ),
            ),
            result = StablePatchToolExecutionResult.Failure("not applied"),
        ).toHistoryHeader(2.milliseconds)

        assertEquals(PatchHistoryItemTarget.SingleFile("Main.kt"), single.target)
        assertEquals(PatchHistoryItemTarget.FileCount(2), multiple.target)
        assertEquals(PatchHistoryItemStatus.Failed, single.status)
        assertEquals(PatchHistoryItemStatus.Failed, multiple.status)
    }
}
