package io.github.stream29.kodex.cli.session

import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.cli.agent.AgentRuntimeHistoryViewModelFactory
import io.github.stream29.kodex.cli.agent.DefaultComposerViewModelFactory
import io.github.stream29.kodex.cli.agent.createAgentRuntimeViewModel
import io.github.stream29.kodex.cli.history.createAgentHistoryViewModel

internal class SessionViewModelCreationProbe {
    private val mutableAgents = mutableListOf<AgentViewModel>()
    private val mutableHistories = mutableListOf<AgentHistoryViewModel>()

    val agents: List<AgentViewModel> get() = mutableAgents.toList()
    val histories: List<AgentHistoryViewModel> get() = mutableHistories.toList()

    internal fun recordAgent(
        viewModel: AgentViewModel,
    ) {
        mutableAgents += viewModel
    }

    internal fun recordHistory(
        viewModel: AgentHistoryViewModel,
    ) {
        mutableHistories += viewModel
    }
}

internal fun testSessionAgentViewModelFactory(
    probe: SessionViewModelCreationProbe? = null,
): PersistedSessionAgentViewModelFactory =
    PersistedSessionAgentViewModelFactory { session, ownerScope ->
        val created = createAgentRuntimeViewModel(
            session = session,
            ownerScope = ownerScope,
            composerFactory = DefaultComposerViewModelFactory,
            historyFactory = AgentRuntimeHistoryViewModelFactory { agentSession, childScope ->
                createAgentHistoryViewModel(agentSession.runtime, childScope).also { history ->
                    probe?.recordHistory(history)
                }
            },
        )
        probe?.recordAgent(created)
        created
    }

internal fun testSessionViewModelRegistry(
    repository: io.github.stream29.kodex.agentsession.contract.KodexRootSessionRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    probe: SessionViewModelCreationProbe? = null,
): DefaultPersistedSessionViewModelRegistry =
    testSessionViewModelRegistry(
        repositoryFactory = KodexSessionRepositoryFactory { repository },
        scope = scope,
        probe = probe,
    )

internal fun testSessionViewModelRegistry(
    repositoryFactory: KodexSessionRepositoryFactory,
    scope: kotlinx.coroutines.CoroutineScope,
    probe: SessionViewModelCreationProbe? = null,
): DefaultPersistedSessionViewModelRegistry =
    DefaultPersistedSessionViewModelRegistry(
        repositoryFactory = repositoryFactory,
        scope = scope,
        agentFactory = testSessionAgentViewModelFactory(probe),
    )
