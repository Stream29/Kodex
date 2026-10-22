package io.github.stream29.kodex.cli.agent

import io.github.stream29.kodex.app.agent.contract.ComposerDependencies
import io.github.stream29.kodex.app.agent.contract.ComposerOwnerId
import io.github.stream29.kodex.app.agent.contract.ComposerViewModel
import io.github.stream29.kodex.app.agent.contract.ComposerViewModelFactory
import kotlinx.coroutines.CoroutineScope

/** Creates one dependency-only Composer child for an exact owner identity. */
public expect fun createComposerViewModel(
    ownerId: ComposerOwnerId,
    dependencies: ComposerDependencies,
    ownerScope: CoroutineScope,
): ComposerViewModel

/** Typed assembly entrypoint for the Composer component. */
public expect val composerViewModelFactory: ComposerViewModelFactory
