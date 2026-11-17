package io.github.stream29.kodex.app.usagereset

import io.github.stream29.kodex.app.usagereset.contract.UsageResetDependencies
import io.github.stream29.kodex.app.usagereset.contract.UsageResetViewModel
import kotlinx.coroutines.CoroutineScope

/**
 * Creates an initially hidden child on [ownerScope]'s dispatcher with a linked
 * local Job. Construction performs no I/O. Host closes the child explicitly;
 * cancellation propagates from owner to child, never from child to shared owner.
 */
public expect fun createUsageResetViewModel(
    dependencies: UsageResetDependencies,
    ownerScope: CoroutineScope,
): UsageResetViewModel
