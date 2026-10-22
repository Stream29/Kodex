@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.app.runtimeconfiguration

import kotlinx.coroutines.CoroutineScope

public expect object DefaultRuntimeConfigurationViewModelFactory : RuntimeConfigurationViewModelFactory {
    override fun create(
        dependencies: RuntimeConfigurationDependencies,
        ownerScope: CoroutineScope,
    ): RuntimeConfigurationViewModel
}

public expect fun createRuntimeConfigurationViewModel(
    dependencies: RuntimeConfigurationDependencies,
    ownerScope: CoroutineScope,
): RuntimeConfigurationViewModel
