package io.github.stream29.kodex.tool.currenttime

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableTextToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingFunctionToolEvent
import io.github.stream29.kodex.openai.ResponsesApiNamespace
import io.github.stream29.kodex.tool.builder.ToolBuilderJson
import kotlinx.serialization.json.jsonObject
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
            (tool as io.github.stream29.kodex.openai.ResponsesApiTool).name
        })
        assertTrue((namespace.tools.single() as io.github.stream29.kodex.openai.ResponsesApiTool).outputSchema != null)
    }

    test("normal Responses output is model-facing text") {
        val fixedClock = object : Clock {
            override fun now(): Instant = Instant.parse("2026-07-16T08:09:10Z")
        }
        val completed = assertIs<StableTextToolEvent>(
            CurrentTimeTools.createTool(CurrentTimeToolClient(fixedClock)).handle(
                PendingFunctionToolEvent(
                name = CurrentTimeToolName,
                namespace = CurrentTimeNamespace,
                arguments = ToolBuilderJson.parseToJsonElement("{}"),
                callId = "call_1",
            ),
            ),
        )

        assertEquals("It is 2026-07-16 08:09:10 UTC.", completed.result)
    }
}
