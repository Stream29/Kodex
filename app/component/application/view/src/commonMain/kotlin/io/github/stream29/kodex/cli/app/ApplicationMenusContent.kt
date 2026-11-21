package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.*
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.agent.contract.AgentLifecycleState
import io.github.stream29.kodex.app.application.contract.ApplicationPopupState
import io.github.stream29.kodex.app.application.contract.ApplicationViewModel
import io.github.stream29.kodex.app.application.contract.ApplicationNavigationState
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.session.contract.AgentTabViewModel
import io.github.stream29.kodex.cli.agent.canEditHistory
import io.github.stream29.kodex.cli.historyindex.HistoryIndexCommand
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
    val currentAgent by rememberUpdatedState(selectedAgent)
    val focus by rememberUpdatedState(focusEditedComposer)
    val report by rememberUpdatedState(onOperationFailure)
    fun consumeTab(request: ApplicationTabMenuTarget): Boolean =
        renderer.isTabPlaced(request.placement) &&
            viewModel.navigation.value.tabs.any { it === request.target } && acceptTab(request)
    fun isCurrentHistory(request: ApplicationHistoryMenuOpening): Boolean {
        val agent = request.agent
        return currentAgent === agent && viewModel.isSelectedHistoryAgent(request.tab, agent) && agent.canEditBoundHistory() &&
            agent.history.historyItems.value.generation == request.generation &&
            agent.history.contains(request.generation, request.storageIndex)
    }
    fun consumeHistory(request: ApplicationHistoryMenuOpening): Boolean =
        renderer.isHistoryPlaced(request.placement) && isCurrentHistory(request) && acceptHistory(request)
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
        onClose = { request -> if (consumeTab(request)) command { viewModel.closeTab(request.target) } },
        onCloseAndArchive = { request ->
            val root = request.target as? PersistedSessionViewModel
            if (root != null && consumeTab(request)) command { viewModel.closeAndArchiveSession(root) }
        },
        onRename = { request ->
            val root = request.target as? PersistedSessionViewModel
            if (root != null && consumeTab(request)) command { viewModel.openRenameSessionPopup(root) }
        },
        onDelete = { request ->
            val root = request.target as? PersistedSessionViewModel
            if (root != null && consumeTab(request)) command { viewModel.openDeleteSessionPopup(root) }
        },
    )
    ApplicationHistoryMenuContent(
        historyOpening, navigation.selectedTab, selectedAgent, renderer,
        onDismiss = { acceptHistory(it) },
        onRevert = { request ->
            if (consumeHistory(request)) command {
                if (!isCurrentHistory(request)) return@command
                viewModel.editHistory(request.tab, request.agent, request.storageIndex, request.generation,
                    HistoryIndexCommand.Revert, null, focus)
            }
        },
        onRevertAndEdit = { request, text ->
            if (consumeHistory(request)) command {
                if (!isCurrentHistory(request)) return@command
                viewModel.editHistory(request.tab, request.agent, request.storageIndex, request.generation,
                    HistoryIndexCommand.RevertAndEdit, text, focus)
            }
        },
        onFork = { request ->
            if (consumeHistory(request)) command {
                if (!isCurrentHistory(request)) return@command
                viewModel.editHistory(request.tab, request.agent, request.storageIndex, request.generation,
                    HistoryIndexCommand.Fork, null, focus)
            }
        },
    )
    AgentHistoryRevertContent(selectedAgent, renderer)
}

internal fun ApplicationViewModel.isSelectedHistoryAgent(tab: AgentTabViewModel, agent: AgentViewModel): Boolean =
    navigation.value.selectedTab === tab && tab.agent.value === agent &&
        agent.lifecycle.value == AgentLifecycleState.Open && popup.value == ApplicationPopupState.Closed

internal fun AgentViewModel.canEditBoundHistory(): Boolean = state.value.canEditHistory(running.value)

internal suspend fun ApplicationViewModel.editHistory(
    tab: AgentTabViewModel, agent: AgentViewModel, index: Int, generation: Long,
    command: HistoryIndexCommand, text: String?, focus: (AgentViewModel) -> Unit,
) {
    when (command) {
        HistoryIndexCommand.GoTo -> error("Go to does not edit history.")
        HistoryIndexCommand.Revert -> agent.requestHistoryRevert(index + 1, generation)
        HistoryIndexCommand.Fork -> openSession(agent.forkHistory(index + 1, generation))
        HistoryIndexCommand.RevertAndEdit -> {
            revertAndEdit(agent, index, generation, requireNotNull(text))
            withFrameNanos { }
            if (isSelectedHistoryAgent(tab, agent)) focus(agent)
        }
    }
}
