package io.github.stream29.kodex.app.accountusage

import kotlinx.coroutines.CoroutineScope

/** Create one short-lived observing child; the host retains navigation refresh responsibility. */
public expect fun createAccountUsageViewModel(
    dependencies: AccountUsageDependencies,
    ownerScope: CoroutineScope,
): AccountUsageViewModel
