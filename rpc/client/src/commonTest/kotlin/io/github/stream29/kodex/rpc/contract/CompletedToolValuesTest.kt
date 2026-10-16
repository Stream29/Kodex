package io.github.stream29.kodex.rpc.contract

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSuggestSubagentTaskResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSuggestSubagentTaskToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTextToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWorkEvent
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.tool.multiagent.SuggestSubagentTaskArgs
import io.github.stream29.kodex.tool.multiagent.SuggestSubagentTaskResponse
import io.github.stream29.kodex.tool.multiagent.SuggestedSubagentTask
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputAnswer
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

val completedToolValuesTest by testSuite {
    val serializer = StableCleanEvent.CompletedTool.serializer()
    val answer = StableRequestUserInputToolEvent(
        callId = "answer-1",
        arguments = RequestUserInputArgs(listOf(RequestUserInputQuestion("q1", "Topic", "Which?"))),
        result = StableRequestUserInputResult.Answered(
            RequestUserInputResponse(mapOf("q1" to RequestUserInputAnswer(listOf("Other answer")))),
        ),
    )
    val suggestion = StableSuggestSubagentTaskToolEvent(
        callId = "suggest-1",
        arguments = SuggestSubagentTaskArgs(listOf(SuggestedSubagentTask("Task", "Prompt"))),
        result = StableSuggestSubagentTaskResult.Completed(
            SuggestSubagentTaskResponse.Rejected(feedback = "Not now"),
        ),
    )
    val work = StableTextToolEvent(
        callId = "work-1",
        name = "tool",
        arguments = Json.parseToJsonElement("""{"n":1}"""),
        result = "user interrupt",
        success = false,
    )

    test("completion union round trips both frontend interactions and work events") {
        for (event in listOf(
            answer,
            answer.copy(result = StableRequestUserInputResult.Failure("Cancelled")),
            suggestion,
            suggestion.copy(result = StableSuggestSubagentTaskResult.Failure("Failed")),
            work,
        )) {
            assertEquals(event, Json.decodeFromString(serializer, Json.encodeToString(serializer, event)))
        }
    }

    test("completion encoding agrees with the existing timeline unions") {
        for (event in listOf(answer, suggestion)) {
            assertEquals(
                Json.encodeToJsonElement(StableIndexEvent.serializer(), event),
                Json.encodeToJsonElement(serializer, event),
            )
        }
        assertEquals(
            Json.encodeToJsonElement(StableWorkEvent.serializer(), work),
            Json.encodeToJsonElement(serializer, work),
        )
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
