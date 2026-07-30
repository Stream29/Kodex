package io.github.stream29.codex.lite.agentstorage.cleanmodels.stable

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.codex.lite.openai.FunctionCallOutputBody
import io.github.stream29.codex.lite.openai.FunctionCallOutputPayload
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponseItemId
import io.github.stream29.codex.lite.utils.applypatch.PatchAffectedPaths
import io.github.stream29.codex.lite.utils.applypatch.PatchApplyResult
import io.github.stream29.codex.lite.utils.applypatch.PatchChange
import io.github.stream29.codex.lite.utils.applypatch.PatchDelta
import io.github.stream29.codex.lite.utils.applypatch.PatchFileChange
import io.github.stream29.codex.lite.utils.applypatch.parsePatch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals

private val json = Json

val stablePatchToolEventSerializationTest by testSuite {
    test("round trips successful patch tool event") {
        val patchText = """
            *** Begin Patch
            *** Update File: old.txt
            *** Move to: new.txt
            @@ heading
            -old
            +new
            *** End Patch
        """.trimIndent()
        val event = StablePatchToolEvent(
            callId = "call_apply_patch",
            itemId = ResponseItemId("item_apply_patch"),
            diff = patchText.parsePatch(),
            result = StablePatchToolExecutionResult.Success(
                applyResult = PatchApplyResult(
                    affectedPaths = PatchAffectedPaths(
                        added = emptyList(),
                        modified = listOf("new.txt"),
                        deleted = emptyList(),
                    ),
                    delta = PatchDelta(
                        changes = listOf(
                            PatchChange(
                                path = "old.txt",
                                change = PatchFileChange.Update(
                                    movePath = "new.txt",
                                    oldContent = "old\n",
                                    overwrittenMoveContent = null,
                                    newContent = "new\n",
                                ),
                            ),
                        ),
                        exact = true,
                    ),
                ),
            ),
        )

        val encoded = json.encodeToString(event)
        val element = json.parseToJsonElement(encoded).jsonObject

        assertEquals(
            setOf("call_id", "item_id", "diff", "result"),
            element.keys,
        )
        val firstHunkType = element["diff"]
            ?.jsonObject
            ?.get("hunks")
            ?.jsonArray
            ?.first()
            ?.jsonObject
            ?.get("type")
        assertEquals(JsonPrimitive("update_file"), firstHunkType)
        assertEquals(JsonPrimitive("success"), element["result"]?.jsonObject?.get("type"))
        assertEquals(event, json.decodeFromString<StablePatchToolEvent>(encoded))
        assertEquals(
            listOf(
                ResponseItem.CustomToolCall(
                    id = event.itemId,
                    callId = event.callId,
                    name = "apply_patch",
                    input = event.diff.patch,
                ),
                ResponseItem.CustomToolCallOutput(
                    callId = event.callId,
                    output = FunctionCallOutputPayload(
                        body = FunctionCallOutputBody.Text("Success. Patch applied."),
                        success = true,
                    ),
                ),
            ),
            event.toResponseHistoryItems(),
        )
    }

    test("round trips failed patch tool event") {
        val patchText = """
                *** Begin Patch
                *** Delete File: missing.txt
                *** End Patch
                """.trimIndent()
        val event = StablePatchToolEvent(
            callId = "call_apply_patch",
            diff = patchText.parsePatch(),
            result = StablePatchToolExecutionResult.Failure("File does not exist: missing.txt"),
        )

        val encoded = json.encodeToString(event)
        val element = json.parseToJsonElement(encoded).jsonObject

        assertEquals(JsonPrimitive("failure"), element["result"]?.jsonObject?.get("type"))
        assertEquals(event, json.decodeFromString<StablePatchToolEvent>(encoded))
        assertEquals(
            ResponseItem.CustomToolCallOutput(
                callId = event.callId,
                output = FunctionCallOutputPayload(
                    body = FunctionCallOutputBody.Text("File does not exist: missing.txt"),
                    success = false,
                ),
            ),
            event.toResponseHistoryItems().last(),
        )
    }
}
