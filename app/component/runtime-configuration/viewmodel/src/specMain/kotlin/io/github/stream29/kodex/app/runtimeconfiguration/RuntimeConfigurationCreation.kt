package io.github.stream29.kodex.app.runtimeconfiguration

import kotlinx.coroutines.CoroutineScope

/** Creates local observation only: no writes, source close, store, active-owner lookup or I/O. */
public expect fun createRuntimeConfigurationViewModel(
    dependencies: RuntimeConfigurationDependencies,
    ownerScope: CoroutineScope,
): RuntimeConfigurationViewModel
