package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.FunctionCallOutputBody
import io.github.stream29.kodex.openai.FunctionCallOutputContentItem
import io.github.stream29.kodex.openai.ImageDetail
import io.github.stream29.kodex.openai.PlanItemArg
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import io.github.stream29.kodex.openai.StepStatus
import io.github.stream29.kodex.openai.UpdatePlanArgs
import io.github.stream29.kodex.tool.awaitevent.AwaitEventArguments
import io.github.stream29.kodex.tool.awaitevent.AwaitEventOutput
import io.github.stream29.kodex.tool.readmedia.MediaDetail
import io.github.stream29.kodex.tool.readmedia.ReadMediaToolArguments
import io.github.stream29.kodex.tool.readmedia.ReadMediaToolOutput
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecOutput
import io.github.stream29.kodex.tool.unifiedexec.WriteStdinArguments
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

private val specializedToolJson = Json

val stableSpecializedToolEventSerializationTest by testSuite {
    test("round trips read_media and await_event tool events") {
        val readMedia = StableReadMediaToolEvent(
            callId = "call_read_media",
            itemId = ResponseItemId("item_read_media"),
            arguments = ReadMediaToolArguments(
                path = "/tmp/chart.png",
                detail = MediaDetail.Original,
            ),
            result = StableReadMediaResult.Success(
                ReadMediaToolOutput(
                    imageUrl = "data:image/png;base64,aW1hZ2U=",
                    detail = MediaDetail.Original,
                ),
            ),
        )
        val awaitEvent = StableAwaitEventToolEvent(
            callId = "call_await_event",
            itemId = ResponseItemId("item_await_event"),
            arguments = AwaitEventArguments(terminals = listOf(1, 3)),
            result = StableAwaitEventResult.Output(
                AwaitEventOutput(Instant.parse("2026-11-16T00:00:00Z")),
            ),
        )
        val events: List<StableCleanEvent.CompletedTool> = listOf(readMedia, awaitEvent)

        events.forEach(::assertStableToolEventRoundTrip)
        events.forEach { event -> assertIs<StableWorkEvent>(event) }
        assertEquals(
            listOf("read_media", "await_event"),
            events.map { event -> event.projectedFunctionName() },
        )
        val mediaOutput = assertIs<ResponseItem.FunctionCallOutput>(readMedia.toResponseHistoryItems().last())
        assertEquals(
            FunctionCallOutputBody.ContentItems(
                listOf(
                    FunctionCallOutputContentItem.InputImage(
                        imageUrl = "data:image/png;base64,aW1hZ2U=",
                        detail = ImageDetail.Original,
                    ),
                ),
            ),
            mediaOutput.output.body,
        )
    }

    test("round trips command execution actions") {
        val events = listOf(
            StableCommandExecutionToolEvent(
                callId = "call_exec_command",
                itemId = ResponseItemId("item_exec_command"),
                action = StableCommandExecutionAction.ExecCommand(
                    ExecCommandArguments(
                        command = "./gradlew check",
                        workdir = "Kodex",
                        yieldTimeMillis = 10_000,
                        maxOutputTokens = 20_000,
                    ),
                ),
                result = StableCommandExecutionResult.Output(
                    UnifiedExecOutput(
                        chunkId = "chunk-1",
                        wallTimeSeconds = 1.25,
                        exitCode = 0,
                        originalTokenCount = 120,
                        output = "BUILD SUCCESSFUL",
                    ),
                ),
            ),
            StableCommandExecutionToolEvent(
                callId = "call_write_stdin",
                itemId = ResponseItemId("item_write_stdin"),
                action = StableCommandExecutionAction.WriteStdin(
                    WriteStdinArguments(sessionId = 42, chars = "\u0003", yieldTimeMillis = 250),
                ),
                result = StableCommandExecutionResult.Failure("Session 42 is not running."),
            ),
        )

        events.forEach(::assertStableToolEventRoundTrip)
        events.forEach { event -> assertIs<StableWorkEvent>(event) }
        assertEquals(
            listOf("exec_command", "write_stdin"),
            events.map { event -> event.projectedFunctionName() },
        )
        val execFunctionCall = assertIs<ResponseItem.FunctionCall>(
            events[0].toResponseHistoryItems().first(),
        )
        val writeFunctionCall = assertIs<ResponseItem.FunctionCall>(
            events[1].toResponseHistoryItems().first(),
        )
        assertTrue(""""yield_time_ms":10000""" in execFunctionCall.arguments)
        assertTrue(""""yield_time_ms":250""" in writeFunctionCall.arguments)

        assertFailsWith<SerializationException> {
            specializedToolJson.decodeFromString<StableWorkEvent>(
                """{"type":"command_execution_tool_event","call_id":"call_exec_command","action":{"type":"exec_command","arguments":{"cmd":"./gradlew check"}},"result":{"type":"failure","message":"failed"}}""",
            )
        }
        assertFailsWith<SerializationException> {
            specializedToolJson.decodeFromString<StableWorkEvent>(
                """{"type":"command_execution_tool_event","call_id":"call_write_stdin","action":{"type":"write_stdin","arguments":{"session_id":42}},"result":{"type":"failure","message":"failed"}}""",
            )
        }
    }

    test("round trips plan update event") {
        val event = StablePlanUpdate(
            callId = "call_update_plan",
            itemId = ResponseItemId("item_update_plan"),
            arguments = UpdatePlanArgs(
                explanation = "Current plan",
                plan = listOf(
                    PlanItemArg("Migrate clean tools", StepStatus.InProgress),
                ),
            ),
        )

        assertStableToolEventRoundTrip(event)
        assertIs<StableIndexEvent>(event)
        assertEquals("update_plan", event.projectedFunctionName())
    }
}

private fun assertStableToolEventRoundTrip(event: StableCleanEvent.CompletedTool) {
    val encoded = when (event) {
        is StableIndexEvent.CompletedTool ->
            specializedToolJson.encodeToString<StableIndexEvent>(event)

        is StableWorkEvent.CompletedTool ->
            specializedToolJson.encodeToString<StableWorkEvent>(event)
    }
    val element = specializedToolJson.parseToJsonElement(encoded).jsonObject
    val items = event.toResponseHistoryItems()
    val call = assertIs<ResponseItem.ToolCall>(items.first())
    val output = assertIs<ResponseItem.ToolCallOutput>(items.last())

    val decoded = when (event) {
        is StableIndexEvent.CompletedTool ->
            specializedToolJson.decodeFromString<StableIndexEvent>(encoded)

        is StableWorkEvent.CompletedTool ->
            specializedToolJson.decodeFromString<StableWorkEvent>(encoded)
    }
    assertEquals(event, decoded)
    assertEquals(2, items.size)
    assertEquals(call.callId, output.callId)
    assertTrue("type" in element)
    assertTrue("call" !in element)
    assertTrue("output" !in element)
}

private fun StableCleanEvent.CompletedTool.projectedFunctionName(): String =
    assertIs<ResponseItem.FunctionCall>(toResponseHistoryItems().first()).name
