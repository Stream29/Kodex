package io.github.stream29.kodex.cli.history

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionAction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCustomToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableJsonToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatchToolExecutionResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableReadMediaResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableReadMediaToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableServerToolSearch
import io.github.stream29.kodex.app.history.contract.item.CommandExecutionHistoryAction
import io.github.stream29.kodex.app.history.contract.item.CommandExecutionHistoryResult
import io.github.stream29.kodex.app.history.contract.item.PatchHistoryItemStatus
import io.github.stream29.kodex.app.history.contract.item.PatchHistoryItemTarget
import io.github.stream29.kodex.app.history.contract.item.ToolHistoryItemHeader
import io.github.stream29.kodex.openai.FunctionCallOutputPayload
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.tool.readmedia.MediaDetail
import io.github.stream29.kodex.tool.readmedia.ReadMediaToolArguments
import io.github.stream29.kodex.tool.readmedia.ReadMediaToolOutput
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecOutput
import io.github.stream29.kodex.utils.applypatch.Patch
import io.github.stream29.kodex.utils.applypatch.UpdateFileHunk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

val historyItemHeaderFactoryTest by testSuite {
    test("readMediaHeaderUsesBoundedPathAndMapsFailureStatus") {
        val success = assertIs<ToolHistoryItemHeader.Summary>(
            StableReadMediaToolEvent(
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
            StableReadMediaToolEvent(
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
            StableJsonToolEvent(
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

    test("commandHeaderRetainsOnlyBoundedPresentationAndOutcomeDiscriminators") {
        val event = StableCommandExecutionToolEvent(
            callId = "command",
            action = StableCommandExecutionAction.ExecCommand(
                ExecCommandArguments(
                    command = "printf first\n" + "x".repeat(300),
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
        assertTrue(action.command.startsWith("printf first x"))
        assertTrue(action.command.endsWith("..."))
        assertTrue(action.command.length <= 240)
        assertEquals(
            CommandExecutionHistoryResult.Output(exitCode = 0, sessionId = 7),
            header.result,
        )
        assertEquals(12.milliseconds, header.elapsed)
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
        val event = StableCustomToolEvent(
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
        val single = StablePatchToolEvent(
            callId = "single",
            diff = Patch(
                patch = "",
                hunks = listOf(UpdateFileHunk(path = "src/Main.kt", chunks = emptyList())),
            ),
            result = StablePatchToolExecutionResult.Failure("not applied"),
        ).toHistoryHeader(1.milliseconds)
        val multiple = StablePatchToolEvent(
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
