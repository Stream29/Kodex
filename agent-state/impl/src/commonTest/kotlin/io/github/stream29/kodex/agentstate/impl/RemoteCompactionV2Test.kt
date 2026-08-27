package io.github.stream29.kodex.agentstate.impl

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.PlanItemArg
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.StepStatus
import io.github.stream29.kodex.openai.UpdatePlanArgs
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

val remoteCompactionV2Test by testSuite {
    test("retains user messages and completed plan updates in source order") {
        val firstUser = userMessage("First request.")
        val secondUser = userMessage("Second request.")
        val planUpdate = planUpdate("plan-1")
        val assistant = ResponseItem.Message(
            role = MessageRole.Assistant,
            content = listOf(ContentItem.OutputText("Do not retain this.")),
        )
        val input = buildList<ResponseItem> {
            add(firstUser)
            add(assistant)
            addAll(planUpdate.toResponseHistoryItems())
            add(secondUser)
        }

        assertEquals(
            listOf(
                StableCleanEvent.UserMessage(firstUser.content),
                planUpdate,
                StableCleanEvent.UserMessage(secondUser.content),
            ),
            buildRemoteCompactionV2Prefix(input),
        )
    }

    test("evicts older retained items when the retained window is exhausted") {
        val oldUser = userMessage("Old request.")
        val oversizedUser = userMessage("x".repeat(300_000))
        val planUpdate = planUpdate("plan-2")
        val input = buildList<ResponseItem> {
            add(oldUser)
            add(oversizedUser)
            addAll(planUpdate.toResponseHistoryItems())
        }

        val prefix = buildRemoteCompactionV2Prefix(input)

        assertEquals(2, prefix.size)
        val retainedUser = assertIs<StableCleanEvent.UserMessage>(prefix[0])
        val retainedText = assertIs<ContentItem.InputText>(retainedUser.content.single()).text
        assertTrue(retainedText.contains("tokens truncated"))
        assertEquals(planUpdate, prefix[1])
    }
}

private fun userMessage(text: String): ResponseItem.Message =
    ResponseItem.Message(
        role = MessageRole.User,
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
