@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.app.usagereset

import io.github.stream29.kodex.app.usagereset.contract.UsageResetDependencies
import io.github.stream29.kodex.app.usagereset.contract.UsageResetViewModel
import io.github.stream29.kodex.app.usagereset.contract.UsageResetViewModelFactory
import kotlinx.coroutines.CoroutineScope

/** Dependency-only implementation of the typed spec factory. */
public expect object DefaultUsageResetViewModelFactory : UsageResetViewModelFactory {
    override fun create(
        dependencies: UsageResetDependencies,
        ownerScope: CoroutineScope,
    ): UsageResetViewModel
}

/**
 * Creates an initially hidden child on [ownerScope]'s dispatcher with a linked
 * local Job. Construction performs no I/O. Host closes the child explicitly;
 * cancellation propagates from owner to child, never from child to shared owner.
 */
public expect fun createUsageResetViewModel(
    dependencies: UsageResetDependencies,
    ownerScope: CoroutineScope,
): UsageResetViewModel
