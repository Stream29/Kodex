package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.*
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.application.contract.ApplicationPopupState
import io.github.stream29.kodex.app.application.contract.ApplicationViewModel
import io.github.stream29.kodex.app.application.contract.ApplicationNavigationState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
public actual fun ApplicationMenusContent(
    viewModel: ApplicationViewModel,
    navigation: ApplicationNavigationState,
    selectedAgent: AgentViewModel?,
    tabOpening: ApplicationTabMenuTarget?,
    historyOpening: ApplicationHistoryMenuOpening?,
    renderer: ApplicationMenuRenderer,
    admitTab: (ApplicationTabMenuTarget) -> Boolean,
    admitHistory: (ApplicationHistoryMenuOpening) -> Boolean,
    focusEditedComposer: (AgentViewModel) -> Unit,
    onOperationFailure: (Throwable) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val acceptTab by rememberUpdatedState(admitTab)
    val acceptHistory by rememberUpdatedState(admitHistory)
    val focus by rememberUpdatedState(focusEditedComposer)
    val report by rememberUpdatedState(onOperationFailure)
    fun command(block: suspend () -> Unit) {
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) { report(failure) }
        }
    }
    ApplicationTabMenuContent(
        tabOpening, navigation.tabs.any { it === tabOpening?.target }, renderer,
        onDismiss = { acceptTab(it) },
        onClose = { request -> if (acceptTab(request)) command { viewModel.closeTab(request.target) } },
        onCloseAndArchive = { request -> if (acceptTab(request)) command { viewModel.closeAndArchiveSession(request.target) } },
        onRename = { request -> if (acceptTab(request)) command { viewModel.openRenameSessionPopup(request.target) } },
        onDelete = { request -> if (acceptTab(request)) command { viewModel.openDeleteSessionPopup(request.target.sessionIndex) } },
    )
    ApplicationHistoryMenuContent(
        historyOpening, navigation.selectedSession, selectedAgent, renderer,
        onDismiss = { acceptHistory(it) },
        onRevert = { request ->
            if (acceptHistory(request)) command {
                request.agent.requestHistoryRevert(request.storageIndex + 1, request.generation)
            }
        },
        onRevertAndEdit = { request, text ->
            if (acceptHistory(request)) command {
                revertAndEdit(request.agent, request.storageIndex, request.generation, text)
                withFrameNanos { }
                if (viewModel.navigation.value.selectedSession?.rootAgent?.value === request.agent &&
                    viewModel.popup.value == ApplicationPopupState.Closed
                ) focus(request.agent)
            }
        },
        onFork = { request ->
            if (acceptHistory(request)) command {
                val index = request.session.fork(request.agent, request.storageIndex + 1, request.generation)
                viewModel.openSession(index)
            }
        },
    )
    AgentHistoryRevertContent(selectedAgent, renderer)
}
