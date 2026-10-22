package io.github.stream29.kodex.cli.accountusage

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.accountusage.AccountUsageState
import io.github.stream29.kodex.app.accountusage.AccountUsageViewModel

/**
 * Full usage summary/actions renderer. No reset dialogs, local reset state, constructor/disposal
 * refresh or child close on navigation. Set showOperationFailure false when Settings renders
 * the same application-owned flag. The host alone owns automatic page-entry refresh.
 */
@Composable
public expect fun AccountUsageComponent(viewModel: AccountUsageViewModel, showOperationFailure: Boolean = true): Unit

/** Every DTO branch is rendered without fabricating zeroes or crossing into reset ownership. */
@Composable
public expect fun AccountUsageContent(
    state: AccountUsageState,
    onRefresh: () -> Unit,
    onUseReset: () -> Unit,
    onDismissFailure: () -> Unit,
    showOperationFailure: Boolean = true,
): Unit
