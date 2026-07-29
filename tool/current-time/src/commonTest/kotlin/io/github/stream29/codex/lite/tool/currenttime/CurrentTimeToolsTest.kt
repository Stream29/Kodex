@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.stream29.codex.lite.tool.currenttime

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.codex.lite.openai.FunctionCallOutputBody
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesApiNamespace
import io.github.stream29.codex.lite.tool.builder.ToolBuilderJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

val currentTimeToolsTest by testSuite {
    test("formats UTC timestamps like Codex") {
        assertEquals(
            "2026-07-16 08:09:10 UTC",
            Instant.parse("2026-07-16T08:09:10Z").formatCurrentTimeUtc(),
        )
    }

    test("spec declares clock.curr_time with structured code-mode output") {
        val namespace = CurrentTimeTools.spec as ResponsesApiNamespace
        val encoded = ToolBuilderJson.parseToJsonElement(
            ToolBuilderJson.encodeToString(namespace),
        ).jsonObject

        assertEquals(CurrentTimeNamespace, encoded["name"]?.toString()?.trim('"'))
        assertEquals(CurrentTimeToolName, namespace.tools.single().let { tool ->
            (tool as io.github.stream29.codex.lite.openai.ResponsesApiTool).name
        })
        assertTrue((namespace.tools.single() as io.github.stream29.codex.lite.openai.ResponsesApiTool).outputSchema != null)
    }

    test("normal Responses output is model-facing text") {
        val fixedClock = object : Clock {
            override fun now(): Instant = Instant.parse("2026-07-16T08:09:10Z")
        }
        val output = CurrentTimeTools.createTool(CurrentTimeToolClient(fixedClock)).handle(
            ResponseItem.FunctionCall(
                name = CurrentTimeToolName,
                namespace = CurrentTimeNamespace,
                arguments = "{}",
                callId = "call_1",
            ),
        ).first as ResponseItem.FunctionCallOutput

        val body = output.output.body as FunctionCallOutputBody.Text
        assertEquals("It is 2026-07-16 08:09:10 UTC.", body.text)
    }
}
