package io.github.stream29.kodex.agentstate.impl

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.CleanCompactionCheckpoint
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.RemoteCompactionV2RetainedItem
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputToolEvent
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.PlanItemArg
import io.github.stream29.kodex.openai.StepStatus
import io.github.stream29.kodex.openai.UpdatePlanArgs
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputAnswer
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

val remoteCompactionV2Test by testSuite {
    test("filters retained stable events in source order") {
        val firstUser = userEvent("First request.")
        val secondUser = userEvent("Second request.")
        val planUpdate = planUpdate("plan-1")
        val requestUserInput = answeredRequestUserInput("request-user-input-1")
        val assistant = StableCleanEvent.AssistantMessage(
            content = listOf(ContentItem.OutputText("Do not retain this.")),
        )

        assertEquals(
            listOf(firstUser, planUpdate, requestUserInput, secondUser),
            buildRemoteCompactionV2Prefix(
                listOf(firstUser, assistant, planUpdate, requestUserInput, secondUser),
            ),
        )
    }

    test("retains failed request user input as one stable event") {
        val requestUserInput = failedRequestUserInput()

        assertEquals(
            listOf(requestUserInput),
            buildRemoteCompactionV2Prefix(listOf(requestUserInput)),
        )
    }

    test("round trips the retained prefix and stable encrypted compaction") {
        val prefix: List<RemoteCompactionV2RetainedItem> = listOf(
            userEvent("Persist the request."),
            planUpdate("plan-serialization"),
            answeredRequestUserInput("request-user-input-serialization"),
        )
        val checkpoint = CleanCompactionCheckpoint(
            prefix = prefix,
            historyBaseIndex = 4,
            windowNumber = 1,
            firstWindowId = "window-0",
            previousWindowId = "window-0",
            windowId = "window-1",
        )
        val contextCompaction: StableCleanEvent =
            StableCleanEvent.ContextCompaction(encryptedContent = "encrypted")

        val checkpointJson = OpenAiJsonCodec.encodeToString(
            CleanCompactionCheckpoint.serializer(),
            checkpoint,
        )
        val contextCompactionJson = OpenAiJsonCodec.encodeToString(
            StableCleanEvent.serializer(),
            contextCompaction,
        )

        assertEquals(
            checkpoint,
            OpenAiJsonCodec.decodeFromString(
                CleanCompactionCheckpoint.serializer(),
                checkpointJson,
            ),
        )
        assertEquals(
            contextCompaction,
            OpenAiJsonCodec.decodeFromString(
                StableCleanEvent.serializer(),
                contextCompactionJson,
            ),
        )
    }

    test("evicts older retained events when the retained window is exhausted") {
        val oldUser = userEvent("Old request.")
        val oversizedUser = userEvent("x".repeat(300_000))
        val planUpdate = planUpdate("plan-2")
        val requestUserInput = answeredRequestUserInput("request-user-input-3")

        val prefix = buildRemoteCompactionV2Prefix(
            listOf(oldUser, oversizedUser, planUpdate, requestUserInput),
        )

        assertEquals(3, prefix.size)
        val retainedUser = assertIs<StableCleanEvent.UserMessage>(prefix[0])
        val retainedText = assertIs<ContentItem.InputText>(retainedUser.content.single()).text
        assertTrue(retainedText.contains("tokens truncated"))
        assertEquals(planUpdate, prefix[1])
        assertEquals(requestUserInput, prefix[2])
    }
}

private fun userEvent(text: String): StableCleanEvent.UserMessage =
    StableCleanEvent.UserMessage(
        content = listOf(ContentItem.InputText(text)),
    )

private fun planUpdate(callId: String): StablePlanUpdate =
    StablePlanUpdate(
        callId = callId,
        arguments = UpdatePlanArgs(
            explanation = "Keep the current implementation plan.",
            plan = listOf(
                PlanItemArg(
                    step = "Retain plan updates after compaction.",
                    status = StepStatus.InProgress,
                ),
            ),
        ),
    )

private fun answeredRequestUserInput(callId: String): StableRequestUserInputToolEvent =
    StableRequestUserInputToolEvent(
        callId = callId,
        arguments = requestUserInputArgs(),
        result = StableRequestUserInputResult.Answered(
            RequestUserInputResponse(
                answers = mapOf(
                    "scope" to RequestUserInputAnswer(listOf("Current module")),
                ),
            ),
        ),
    )

private fun failedRequestUserInput(): StableRequestUserInputToolEvent =
    StableRequestUserInputToolEvent(
        callId = "request-user-input-2",
        arguments = requestUserInputArgs(),
        result = StableRequestUserInputResult.Failure("User input unavailable."),
    )

private fun requestUserInputArgs(): RequestUserInputArgs =
    RequestUserInputArgs(
        questions = listOf(
            RequestUserInputQuestion(
                id = "scope",
                header = "Scope",
                question = "Which scope should be used?",
            ),
        ),
    )
