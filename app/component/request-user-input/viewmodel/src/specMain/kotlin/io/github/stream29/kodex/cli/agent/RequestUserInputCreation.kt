package io.github.stream29.kodex.cli.agent

import io.github.stream29.kodex.app.agent.contract.RequestUserInputDependencies
import io.github.stream29.kodex.app.agent.contract.RequestUserInputViewModel
import io.github.stream29.kodex.app.agent.contract.RequestUserInputViewModelFactory
import kotlinx.coroutines.CoroutineScope

/**
 * Creates a dependency-only draft owner with the lifecycle/admission contract of
 * [RequestUserInputViewModelFactory.create]; no runtime or repository is constructed here.
 */
public expect fun createRequestUserInputViewModel(
    dependencies: RequestUserInputDependencies,
    ownerScope: CoroutineScope,
): RequestUserInputViewModel

/** Typed assembly entrypoint with the ownership contract declared in component spec. */
public expect val requestUserInputViewModelFactory: RequestUserInputViewModelFactory
