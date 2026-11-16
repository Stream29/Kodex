package io.github.stream29.kodex.agentstate.tool

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingAwaitEventToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCommandExecutionAction
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCommandExecutionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingFunctionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingInvalidToolCall
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingInvalidToolInvocation
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPatchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingReadMediaToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.openai.PlanItemArg
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import io.github.stream29.kodex.openai.StepStatus
import io.github.stream29.kodex.openai.UpdatePlanArgs
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.kodex.tool.applypatch.ApplyPatchTools
import io.github.stream29.kodex.tool.awaitevent.AwaitEventArguments
import io.github.stream29.kodex.tool.awaitevent.AwaitEventTools
import io.github.stream29.kodex.tool.readmedia.MediaDetail
import io.github.stream29.kodex.tool.readmedia.ReadMediaToolArguments
import io.github.stream29.kodex.tool.readmedia.ReadMediaTools
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecTools
import io.github.stream29.kodex.tool.unifiedexec.WriteStdinArguments
import io.github.stream29.kodex.utils.applypatch.parsePatch
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

val pendingToolEventProjectionTest by testSuite {
    test("projects specialized function calls into direct pending branches") {
        val exec = ExecCommandArguments(
            command = "./gradlew check",
            workdir = "Kodex",
            yieldTimeMillis = 10_000,
        )
        val readMedia = ReadMediaToolArguments(path = "diagram.png", detail = MediaDetail.Original)
        val planArgs = UpdatePlanArgs(
            explanation = "Cut over model surface.",
            plan = listOf(PlanItemArg("Verify tools.", StepStatus.InProgress)),
        )
        val awaitEvent = AwaitEventArguments(terminals = listOf(3, 9))
        val cases: List<Pair<ResponseItem.FunctionCall, PendingToolEvent>> = listOf(
            functionCall(
                UnifiedExecTools.ExecCommandName,
                ExecCommandArguments.serializer(),
                exec,
            ) to PendingCommandExecutionToolEvent(
                callId = "call_${UnifiedExecTools.ExecCommandName}",
                itemId = ItemId,
                action = PendingCommandExecutionAction.ExecCommand(exec),
            ),
            functionCall(
                AwaitEventTools.Name,
                AwaitEventArguments.serializer(),
                awaitEvent,
            ) to PendingAwaitEventToolEvent(
                callId = "call_${AwaitEventTools.Name}",
                itemId = ItemId,
                arguments = awaitEvent,
            ),
            functionCall(
                ReadMediaTools.Name,
                ReadMediaToolArguments.serializer(),
                readMedia,
            ) to PendingReadMediaToolEvent(
                callId = "call_${ReadMediaTools.Name}",
                itemId = ItemId,
                arguments = readMedia,
            ),
            functionCall(
                "update_plan",
                UpdatePlanArgs.serializer(),
                planArgs,
            ) to PendingPlanUpdate(
                callId = "call_update_plan",
                itemId = ItemId,
                arguments = planArgs,
            ),
        )

        cases.forEach { (call, expected) ->
            assertEquals(expected, call.toPendingToolEvent())
            val projected = assertIs<ResponseItem.FunctionCall>(
                expected.toResponseHistoryItems().single(),
            )
            assertEquals(call.id, projected.id)
            assertEquals(call.callId, projected.callId)
            assertEquals(call.name, projected.name)
            assertEquals(call.namespace, projected.namespace)
            assertEquals(expected, projected.toPendingToolEvent())
        }
    }

    test("projects retired client tool search to invalid tool call and apply patch to patch event") {
        val searchArguments = buildJsonObject {
            put("query", "connected drive tools")
            put("limit", 3)
        }
        val searchCall = ResponseItem.ClientToolSearchCall(
            id = ItemId,
            callId = "call_search",
            arguments = searchArguments,
        )
        val patchText = """
            *** Begin Patch
            *** Delete File: obsolete.txt
            *** End Patch
        """.trimIndent()
        val patchCall = ResponseItem.CustomToolCall(
            id = ItemId,
            callId = "call_patch",
            name = ApplyPatchTools.Name,
            input = patchText,
        )

        assertEquals(
            PendingInvalidToolCall(
                callId = "call_search",
                itemId = ItemId,
                invocation = PendingInvalidToolInvocation.Function(
                    name = "tool_search",
                    namespace = null,
                    arguments = searchArguments.toString(),
                ),
                message = "Client tool_search is no longer supported.",
            ),
            searchCall.toPendingToolEvent(),
        )
        assertEquals(
            PendingPatchToolEvent("call_patch", ItemId, patchText.parsePatch()),
            patchCall.toPendingToolEvent(),
        )
    }

    test("routes parse failures and missing or invalid yield_time_ms to pending invalid tool call") {
        val invalidCalls = listOf(
            ResponseItem.FunctionCall(
                id = ItemId,
                callId = "call_invalid",
                name = UnifiedExecTools.ExecCommandName,
                arguments = """{"cmd":""",
            ),
            ResponseItem.FunctionCall(
                id = ItemId,
                callId = "call_exec_missing_yield",
                name = UnifiedExecTools.ExecCommandName,
                arguments = """{"cmd":"pwd"}""",
            ),
            ResponseItem.FunctionCall(
                id = ItemId,
                callId = "call_exec_null_yield",
                name = UnifiedExecTools.ExecCommandName,
                arguments = """{"cmd":"pwd","yield_time_ms":null}""",
            ),
            ResponseItem.FunctionCall(
                id = ItemId,
                callId = "call_exec_bad_yield",
                name = UnifiedExecTools.ExecCommandName,
                arguments = """{"cmd":"pwd","yield_time_ms":"10s"}""",
            ),
            ResponseItem.FunctionCall(
                id = ItemId,
                callId = "call_await_bad_terminals",
                name = AwaitEventTools.Name,
                arguments = """{"terminals":"not-an-array"}""",
            ),
        )

        invalidCalls.forEach { call ->
            val event = assertIs<PendingInvalidToolCall>(
                call.toPendingToolEvent(),
                "Expected PendingInvalidToolCall for ${call.callId}",
            )
            assertEquals(call.callId, event.callId)
            assertEquals(call.id, event.itemId)
            assertEquals(
                PendingInvalidToolInvocation.Function(
                    name = call.name,
                    arguments = call.arguments,
                ),
                event.invocation,
            )
            assertEquals(listOf(call), event.toResponseHistoryItems())
        }
    }

    test("round trips explicit exec_command yield_time_ms and projects legacy write_stdin as generic function call") {
        listOf(10_000L, 250L, 600_000L).forEach { yieldMillis ->
            val exec = ExecCommandArguments(command = "echo wait", yieldTimeMillis = yieldMillis)
            val execCall = functionCall(
                UnifiedExecTools.ExecCommandName,
                ExecCommandArguments.serializer(),
                exec,
            )
            assertTrue(""""yield_time_ms":$yieldMillis""" in execCall.arguments)
            val execPending = assertIs<PendingCommandExecutionToolEvent>(execCall.toPendingToolEvent())
            assertEquals(PendingCommandExecutionAction.ExecCommand(exec), execPending.action)
            val execProjected = assertIs<ResponseItem.FunctionCall>(
                execPending.toResponseHistoryItems().single(),
            )
            assertTrue(""""yield_time_ms":$yieldMillis""" in execProjected.arguments)
            assertEquals(execPending, execProjected.toPendingToolEvent())

            val write = WriteStdinArguments(sessionId = 9, chars = "", yieldTimeMillis = yieldMillis)
            val persistedWritePending = PendingCommandExecutionToolEvent(
                callId = "call_$LegacyWriteStdinName",
                itemId = ItemId,
                action = PendingCommandExecutionAction.WriteStdin(write),
            )
            val writeProjected = assertIs<ResponseItem.FunctionCall>(
                persistedWritePending.toResponseHistoryItems().single(),
            )
            assertTrue(""""yield_time_ms":$yieldMillis""" in writeProjected.arguments)
            assertEquals(
                PendingFunctionToolEvent(
                    callId = "call_$LegacyWriteStdinName",
                    itemId = ItemId,
                    name = LegacyWriteStdinName,
                    namespace = null,
                    arguments = OpenAiJsonCodec.parseToJsonElement(writeProjected.arguments),
                ),
                writeProjected.toPendingToolEvent(),
            )
        }
    }

    test("routes unknown namespaced function calls to generic pending function events") {
        val arguments = buildJsonObject { put("query", "design context") }
        val function = ResponseItem.FunctionCall(
            id = ItemId,
            callId = "call_dynamic",
            namespace = "google_drive",
            name = "search",
            arguments = arguments.toString(),
        )

        assertEquals(
            PendingFunctionToolEvent(
                callId = function.callId,
                itemId = function.id,
                name = function.name,
                namespace = function.namespace,
                arguments = arguments,
            ),
            function.toPendingToolEvent(),
        )
    }
}

private val ItemId: ResponseItemId = ResponseItemId("item_1")
private const val LegacyWriteStdinName: String = "write_stdin"

private fun <Arguments> functionCall(
    name: String,
    serializer: SerializationStrategy<Arguments>,
    arguments: Arguments,
    namespace: String? = null,
): ResponseItem.FunctionCall =
    ResponseItem.FunctionCall(
        id = ItemId,
        callId = "call_$name",
        namespace = namespace,
        name = name,
        arguments = OpenAiJsonCodec.encodeToString(serializer, arguments),
    )
