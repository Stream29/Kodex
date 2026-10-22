package io.github.stream29.kodex.cli.agent

import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskDependencies
import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskViewModel
import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskViewModelFactory
import kotlinx.coroutines.CoroutineScope

/**
 * Creates a dependency-only confirmation owner following [SuggestSubagentTaskViewModelFactory.create].
 * Creation, completion and continuation remain separate bound capabilities, not a transaction.
 */
public expect fun createSuggestSubagentTaskViewModel(
    dependencies: SuggestSubagentTaskDependencies,
    ownerScope: CoroutineScope,
): SuggestSubagentTaskViewModel

/** Typed assembly entrypoint with the ownership contract declared in component spec. */
public expect val suggestSubagentTaskViewModelFactory: SuggestSubagentTaskViewModelFactory
