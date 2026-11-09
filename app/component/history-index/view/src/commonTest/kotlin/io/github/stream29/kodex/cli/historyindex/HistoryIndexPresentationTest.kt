package io.github.stream29.kodex.cli.historyindex

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.*
import io.github.stream29.kodex.app.agent.contract.*
import io.github.stream29.kodex.openai.PlanItemArg
import io.github.stream29.kodex.openai.StepStatus
import io.github.stream29.kodex.openai.UpdatePlanArgs
import io.github.stream29.kodex.tool.requestuserinput.*
import kotlin.test.*

val historyIndexPresentationTest by testSuite {
    test("plan details retain typed steps alongside the safe terminal fallback") {
        val event = StablePlanUpdate("plan", arguments = UpdatePlanArgs("  Note  ", listOf(
            PlanItemArg("first", StepStatus.Completed),
            PlanItemArg("   ", StepStatus.Pending),
            PlanItemArg("last  ", StepStatus.InProgress),
        )))
        val presentation = historyIndexDetailPresentation(
            HistoryIndexEntryDetail(HistoryIndexEntryKind.PlanUpdate, "terminal fallback", plan = event),
        )
        val plan = assertNotNull(presentation.plan)
        assertEquals("terminal fallback", presentation.content)
        assertEquals("Note  ", plan.explanation)
        assertEquals(listOf("first", "[empty]", "last"), plan.steps.map { it.step })
        assertEquals(listOf(StepStatus.Completed, StepStatus.Pending, StepStatus.InProgress), plan.steps.map { it.status })
        assertFalse(plan.pending)
        assertNull(presentation.questionRows)
    }

    test("empty plan detail preserves index empty and explanation-only branches") {
        fun presentation(explanation: String?) = historyIndexDetailPresentation(
            HistoryIndexEntryDetail(
                HistoryIndexEntryKind.PlanUpdate, "fallback",
                plan = StablePlanUpdate("plan", arguments = UpdatePlanArgs(explanation, emptyList())),
            ),
        ).plan
        assertEquals("[empty]", assertNotNull(presentation(null)).emptyLabel)
        val explanationOnly = assertNotNull(presentation("  Note  "))
        assertEquals("Note", explanationOnly.explanation)
        assertNull(explanationOnly.emptyLabel)
    }

    test("row values and linear positions stay separate from platform decoration") {
        assertEquals(
            HistoryIndexRowPresentation(HistoryIndexRowValue.Ready("┌● Original"), HistoryIndexRowPosition.Single),
            historyIndexRowPresentation(
                HistoryIndexEntry(7, HistoryIndexEntryKind.UserMessage, "┌● Original"),
                HistoryIndexRowPosition.Single,
            ),
        )
        assertEquals(listOf(HistoryIndexRowPosition.First, HistoryIndexRowPosition.Middle, HistoryIndexRowPosition.Last),
            (0..2).map { historyIndexRowPosition(it, 3) })
        assertEquals(HistoryIndexRowPosition.Single, historyIndexRowPosition(0, 1))
    }

    test("detail presentation preserves display title and full content") {
        assertEquals(
            HistoryIndexDetailPresentation("Assistant final", "Full content", null, false),
            historyIndexDetailPresentation(
                HistoryIndexEntryDetail(HistoryIndexEntryKind.AssistantFinal, "Full content"),
            ),
        )
    }

    test("read-only question projection redacts before any popup renderer and drops the raw fallback") {
        val event = StableRequestUserInputToolEvent(
            "secret", arguments = RequestUserInputArgs(listOf(
                RequestUserInputQuestion("secret", "Password", "Enter password", isSecret = true),
            )),
            result = StableRequestUserInputResult.Answered(RequestUserInputResponse(mapOf(
                "secret" to RequestUserInputAnswer(listOf("SECRET")),
            ))),
        )
        val presentation = historyIndexDetailPresentation(
            HistoryIndexEntryDetail(HistoryIndexEntryKind.RequestUserInput, "SECRET raw fallback", event),
        )
        assertEquals("", presentation.content)
        assertEquals(listOf("Password: Enter password", "[hidden]"), assertNotNull(presentation.questionRows).map { it.value })
        assertFalse(presentation.questionRows.orEmpty().any { "SECRET" in it.value })
    }
}
