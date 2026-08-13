package io.github.stream29.kodex.cli.agent

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingRequestUserInputToolEvent
import io.github.stream29.kodex.app.agent.contract.RequestUserInputDraftAnswer
import io.github.stream29.kodex.app.agent.contract.RequestUserInputState
import io.github.stream29.kodex.app.agent.contract.RequestUserInputSubmissionResult
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestionOption
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

val requestUserInputViewModelTest by testSuite {
    test("declared selection is revision bound") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            val model = RequestUserInputViewModelImpl(runtime, this, resumeRuntime = {})
            val pending = pendingRequest()
            try {
                model.synchronize(pending)
                val initial = assertIs<RequestUserInputState.Pending>(model.state.value)
                assertFalse(initial.canSubmit)

                assertTrue(
                    model.selectOption(
                        callId = pending.callId,
                        questionId = "scope",
                        label = "Current module",
                    ),
                )
                val selected = assertIs<RequestUserInputState.Pending>(model.state.value)
                assertEquals(
                    RequestUserInputDraftAnswer.Option("Current module"),
                    selected.answers["scope"],
                )
                assertTrue(selected.canSubmit)
                assertEquals(
                    RequestUserInputSubmissionResult.StaleRevision,
                    model.submit(pending.callId, initial.revision),
                )
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("a replacement call discards prior drafts") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val model = RequestUserInputViewModelImpl(
                repository.open(repository.create()).runtime,
                this,
                resumeRuntime = {},
            )
            val first = pendingRequest(callId = "call_first")
            val second = pendingRequest(callId = "call_second")
            try {
                model.synchronize(first)
                model.selectOther(first.callId, "scope")
                model.updateFreeForm(first.callId, "scope", "Whole workspace")
                model.synchronize(second)

                val current = assertIs<RequestUserInputState.Pending>(model.state.value)
                assertEquals(second.callId, current.callId)
                assertTrue(current.answers.isEmpty())
                assertFalse(current.canSubmit)
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }
}

private fun pendingRequest(
    callId: String = "call_scope",
): PendingRequestUserInputToolEvent = PendingRequestUserInputToolEvent(
    callId = callId,
    arguments = RequestUserInputArgs(
        questions = listOf(
            RequestUserInputQuestion(
                id = "scope",
                header = "Scope",
                question = "Which scope should be changed?",
                isOther = false,
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
