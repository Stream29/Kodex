package io.github.stream29.kodex.cli.history

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableCommandExecutionAction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableCommandExecutionResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableCommandExecutionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableCustomToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableMcpToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StablePatchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StablePatchToolExecutionResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableServerToolSearch
import io.github.stream29.kodex.app.history.contract.item.CommandExecutionHistoryAction
import io.github.stream29.kodex.app.history.contract.item.CommandExecutionHistoryResult
import io.github.stream29.kodex.app.history.contract.item.PatchHistoryItemStatus
import io.github.stream29.kodex.app.history.contract.item.PatchHistoryItemTarget
import io.github.stream29.kodex.app.history.contract.item.ToolHistoryItemHeader
import io.github.stream29.kodex.openai.CallToolResult
import io.github.stream29.kodex.openai.FunctionCallOutputPayload
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecOutput
import io.github.stream29.kodex.utils.applypatch.Patch
import io.github.stream29.kodex.utils.applypatch.UpdateFileHunk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class HistoryItemHeaderFactoryTest {
    @Test
    fun commandHeaderRetainsOnlyBoundedPresentationAndOutcomeDiscriminators() {
        val event = StableCommandExecutionToolEvent(
            callId = "command",
            action = StableCommandExecutionAction.ExecCommand(
                ExecCommandArguments(
                    command = "printf first\n" + "x".repeat(300),
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

    @Test
    fun serverToolSearchHeaderUsesItsStructuredPaths() {
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
        assertEquals("Search cloud tools: crm, billing", header.summary)
    }

    @Test
    fun customWebHeaderIncludesTheSearchQuery() {
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

    @Test
    fun mcpHeaderMapsIsErrorInsteadOfTreatingItAsSuccess() {
        fun header(isError: Boolean?) = assertIs<ToolHistoryItemHeader.Summary>(
            StableMcpToolEvent(
                callId = "mcp",
                name = "inspect",
                namespace = "filesystem",
                arguments = JsonObject(emptyMap()),
                result = CallToolResult(content = emptyList(), isError = isError),
            ).toHistoryHeader(1.milliseconds),
        )

        assertEquals("completed", header(isError = false).status)
        assertEquals("filesystem.inspect", header(isError = false).summary)
        assertEquals("completed", header(isError = null).status)
        assertEquals("failed", header(isError = true).status)
        assertEquals("Failed to run filesystem.inspect", header(isError = true).summary)
    }

    @Test
    fun patchHeaderUsesBasenameForOneFileAndCountForMultipleFiles() {
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
