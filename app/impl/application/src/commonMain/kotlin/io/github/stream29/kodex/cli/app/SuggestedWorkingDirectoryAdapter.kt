package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskState
import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskViewModel
import io.github.stream29.kodex.app.workingdirectory.contract.WorkingDirectoryDependencies

/** Captures the suggestion owner/call, but merges cwd into its latest pending configuration. */
internal fun bindSuggestedWorkingDirectory(
    suggestion: SuggestSubagentTaskViewModel,
    callId: String,
): WorkingDirectoryDependencies = WorkingDirectoryDependencies { directory ->
    val current = suggestion.state.value as? SuggestSubagentTaskState.Pending
    if (current != null && current.callId == callId && !current.submitting) {
        suggestion.updateConfiguration(callId, current.configuration.copy(cwd = directory))
    }
}
