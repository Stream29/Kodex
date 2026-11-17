package io.github.stream29.kodex.cli.agent

import io.github.stream29.kodex.app.agent.contract.RequestUserInputDependencies
import io.github.stream29.kodex.app.agent.contract.RequestUserInputViewModel
import kotlinx.coroutines.CoroutineScope

/**
 * Creates a dependency-only draft owner observing [RequestUserInputDependencies.pending] in
 * [ownerScope] without closing [ownerScope]; no runtime or repository is constructed here.
 */
public expect fun createRequestUserInputViewModel(
    dependencies: RequestUserInputDependencies,
    ownerScope: CoroutineScope,
): RequestUserInputViewModel
