package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.*
import io.github.stream29.kodex.app.application.contract.ApplicationNavigationState
import io.github.stream29.kodex.app.application.contract.ApplicationViewModel
import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
public actual fun ApplicationPageContent(
    viewModel: ApplicationViewModel,
    navigation: ApplicationNavigationState,
    onOperationFailure: (Throwable) -> Unit,
    newSession: @Composable (NewSessionViewModel, onSubmit: (Long) -> Unit) -> Unit,
    persistedSession: @Composable (PersistedSessionViewModel) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val report by rememberUpdatedState(onOperationFailure)
    val selected = navigation.selectedSession
    if (selected == null) {
        key(ApplicationChildKey(viewModel.newSession)) {
            newSession(viewModel.newSession) { revision ->
                scope.launch {
                    try { viewModel.submitNewSession(revision) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Throwable) { report(failure) }
                }
            }
        }
    } else {
        key(ApplicationChildKey(selected)) { persistedSession(selected) }
    }
}
