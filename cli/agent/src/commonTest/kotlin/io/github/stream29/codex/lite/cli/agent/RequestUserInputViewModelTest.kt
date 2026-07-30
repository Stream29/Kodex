package io.github.stream29.codex.lite.cli.agent

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingRequestUserInputToolEvent
import io.github.stream29.codex.lite.tool.requestuserinput.RequestUserInputAnswer
import io.github.stream29.codex.lite.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.codex.lite.tool.requestuserinput.RequestUserInputQuestion
import io.github.stream29.codex.lite.tool.requestuserinput.RequestUserInputQuestionOption
import io.github.stream29.codex.lite.tool.requestuserinput.RequestUserInputResponse
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

val requestUserInputViewModelTest by testSuite {
    test("declared selection encodes the matching answer and clears after completion") {
        val model = RequestUserInputViewModel()
        val pending = pendingRequest()

        model.synchronize(pending)

        assertFalse(model.state.value.canSubmit)
        model.selectOption(questionId = "scope", label = "Current module")

        val submission = assertNotNull(model.beginSubmission())
        assertEquals(
            RequestUserInputResponse(
                answers = mapOf("scope" to RequestUserInputAnswer(listOf("Current module"))),
            ),
            submission.response,
        )
        assertTrue(model.state.value.isSubmitting)

        model.completeSubmission(pending.callId)

        assertNull(model.state.value.arguments)
        assertFalse(model.state.value.isSubmitting)
    }

    test("automatically added Other encodes a trimmed user note") {
        val model = RequestUserInputViewModel()
        val pending = pendingRequest(isOther = false)

        model.synchronize(pending)
        model.selectOther(questionId = "scope")
        model.updateFreeForm(questionId = "scope", text = "  Whole workspace  ")

        assertEquals(
            RequestUserInputDraftAnswer.FreeForm("  Whole workspace  "),
            model.state.value.answers["scope"],
        )
        val submission = assertNotNull(model.beginSubmission())
        assertEquals(
            RequestUserInputResponse(
                answers = mapOf("scope" to RequestUserInputAnswer(listOf("user_note: Whole workspace"))),
            ),
            submission.response,
        )
    }

    test("new pending call discards a prior call's answer draft") {
        val model = RequestUserInputViewModel()
        val first = pendingRequest(callId = "call_first")
        val second = pendingRequest(callId = "call_second")

        model.synchronize(first)
        model.selectOption(questionId = "scope", label = "Current module")
        model.synchronize(second)

        assertEquals("call_second", model.state.value.callId)
        assertTrue(model.state.value.answers.isEmpty())
        assertFalse(model.state.value.canSubmit)
    }
}

private fun pendingRequest(
    callId: String = "call_scope",
    isOther: Boolean = false,
): PendingRequestUserInputToolEvent = PendingRequestUserInputToolEvent(
    callId = callId,
    arguments = RequestUserInputArgs(
        questions = listOf(
            RequestUserInputQuestion(
                id = "scope",
                header = "Scope",
                question = "Which scope should be changed?",
                isOther = isOther,
                options = listOf(
                    RequestUserInputQuestionOption(
                        label = "Current module",
                        description = "Change only the active module.",
                    ),
                    RequestUserInputQuestionOption(
                        label = "Whole workspace",
                        description = "Change every affected module.",
                    ),
                ),
            ),
        ),
    ),
)
