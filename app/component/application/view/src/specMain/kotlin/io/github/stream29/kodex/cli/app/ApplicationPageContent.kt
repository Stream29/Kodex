package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.application.contract.ApplicationNavigationState
import io.github.stream29.kodex.app.application.contract.ApplicationViewModel
import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel

/**
 * Selects the exact child from one navigation snapshot and wires singleton submission.
 * Slots borrow children; switching/removing this renderer never closes them or stops backend work.
 * The caller supplies the Application's existing failure outlet; cancellation is not reported.
 */
@Composable
public expect fun ApplicationPageContent(
    viewModel: ApplicationViewModel,
    navigation: ApplicationNavigationState,
    onOperationFailure: (Throwable) -> Unit,
    newSession: @Composable (NewSessionViewModel, onSubmit: (Long) -> Unit) -> Unit,
    persistedSession: @Composable (PersistedSessionViewModel) -> Unit,
)
