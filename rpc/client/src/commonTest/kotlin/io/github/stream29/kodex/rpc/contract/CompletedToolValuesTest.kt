package io.github.stream29.kodex.rpc.contract

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableReadMediaResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableReadMediaToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTextToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWorkEvent
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.PlanItemArg
import io.github.stream29.kodex.openai.StepStatus
import io.github.stream29.kodex.openai.UpdatePlanArgs
import io.github.stream29.kodex.tool.readmedia.MediaDetail
import io.github.stream29.kodex.tool.readmedia.ReadMediaToolArguments
import io.github.stream29.kodex.tool.readmedia.ReadMediaToolOutput
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

val completedToolValuesTest by testSuite {
    val serializer = StableCleanEvent.CompletedTool.serializer()
    val planUpdate = StablePlanUpdate(
        callId = "plan-1",
        arguments = UpdatePlanArgs(
            explanation = "Track steps",
            plan = listOf(PlanItemArg("Implement", StepStatus.InProgress)),
        ),
    )
    val readMedia = StableReadMediaToolEvent(
        callId = "media-1",
        arguments = ReadMediaToolArguments(
            path = "/tmp/diagram.png",
            detail = MediaDetail.Original,
        ),
        result = StableReadMediaResult.Success(
            ReadMediaToolOutput(
                imageUrl = "data:image/png;base64,iVBORw0KGgo=",
                detail = MediaDetail.Original,
            ),
        ),
    )
    val work = StableTextToolEvent(
        callId = "work-1",
        name = "tool",
        arguments = Json.parseToJsonElement("""{"n":1}"""),
        result = "user interrupt",
        success = false,
    )

    test("completion union round trips index and work tool events") {
        for (event in listOf(
            planUpdate,
            readMedia,
            readMedia.copy(result = StableReadMediaResult.Failure("Not found")),
            work,
        )) {
            assertEquals(event, Json.decodeFromString(serializer, Json.encodeToString(serializer, event)))
        }
    }

    test("completion encoding agrees with the existing timeline unions") {
        assertEquals(
            Json.encodeToJsonElement(StableIndexEvent.serializer(), planUpdate),
            Json.encodeToJsonElement(serializer, planUpdate),
        )
        for (event in listOf(readMedia, work)) {
            assertEquals(
                Json.encodeToJsonElement(StableWorkEvent.serializer(), event),
                Json.encodeToJsonElement(serializer, event),
            )
        }
    }

    test("non tool and unknown events cannot decode as tool completions") {
        val user = Json.encodeToString(
            StableIndexEvent.serializer(),
            StableUserMessage(listOf(ContentItem.InputText("Not a tool result"))),
        )
        for (encoded in listOf(user, """{"type":"unknown_tool"}""", "null")) {
            assertFailsWith<SerializationException> { Json.decodeFromString(serializer, encoded) }
        }
    }
}
