package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.agent.contract.*
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.tool.multiagent.SuggestSubagentTaskArgs
import io.github.stream29.kodex.tool.multiagent.SuggestedSubagentTask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.files.Path
import kotlin.test.*

val suggestedWorkingDirectoryAdapterTest by testSuite {
    test("captured call updates only cwd in the latest pending batch configuration") {
        val owner = SuggestionOwner()
        val dependencies = bindSuggestedWorkingDirectory(owner, "captured")
        val initial = assertIs<SuggestSubagentTaskState.Pending>(owner.state.value)
        val latest = initial.configuration.copy(model = OpenAiModelId("latest"), serviceTier = ServiceTier.Fast)
        owner.state.value = initial.copy(configuration = latest, feedback = "keep", revision = 3)
        dependencies.select(Path("/selected"))
        assertEquals(listOf("captured" to latest.copy(cwd = Path("/selected"))), owner.updates)
        assertFalse(owner.closed)
    }
    for ((name, state) in listOf(
        "idle" to SuggestSubagentTaskState.Idle,
        "replacement" to suggestionPending().copy(callId = "replacement"),
        "submitting" to suggestionPending().copy(submitting = true),
    )) {
        test("captured call cannot modify $name after the chooser opened") {
            val owner = SuggestionOwner()
            val dependencies = bindSuggestedWorkingDirectory(owner, "captured")
            owner.state.value = state
            dependencies.select(Path("/late"))
            assertTrue(owner.updates.isEmpty())
            assertSame(state, owner.state.value)
            assertFalse(owner.closed)
        }
    }
}

private fun suggestionPending() = SuggestSubagentTaskState.Pending(
    callId = "captured",
    arguments = SuggestSubagentTaskArgs(listOf(SuggestedSubagentTask("task", "prompt"))),
    configuration = SuggestedSessionConfiguration(
        OpenAiModelId("initial"), ReasoningEffort.Low, ServiceTier.Default, Path("/initial"), RequestUserInputMode.AskUser,
    ),
)

private class SuggestionOwner : io.github.stream29.kodex.app.test.SuggestionTestViewModel() {
    override val state = MutableStateFlow<SuggestSubagentTaskState>(suggestionPending())
    val updates = mutableListOf<Pair<String, SuggestedSessionConfiguration>>()
    var closed = false
    override fun updateConfiguration(callId: String, configuration: SuggestedSessionConfiguration): Boolean {
        updates += callId to configuration
        return true
    }
    override fun updateFeedback(callId: String, text: String) = false
    override suspend fun submit(callId: String, expectedRevision: Long, accepted: Boolean) =
        SuggestSubagentTaskSubmissionResult.Stale
    override fun close() { closed = true }
}
