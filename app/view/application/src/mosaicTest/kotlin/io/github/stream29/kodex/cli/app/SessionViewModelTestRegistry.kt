package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.agentsession.contract.KodexRootSessionRepository
import io.github.stream29.kodex.cli.agent.AgentRuntimeHistoryViewModelFactory
import io.github.stream29.kodex.cli.agent.DefaultComposerViewModelFactory
import io.github.stream29.kodex.cli.agent.createAgentRuntimeViewModel
import io.github.stream29.kodex.cli.history.createAgentHistoryViewModel
import io.github.stream29.kodex.cli.session.DefaultPersistedSessionViewModelRegistry
import io.github.stream29.kodex.cli.session.KodexSessionRepositoryFactory
import io.github.stream29.kodex.cli.session.PersistedSessionAgentViewModelFactory
import kotlinx.coroutines.CoroutineScope

internal fun testSessionViewModelRegistry(
    repository: KodexRootSessionRepository,
    scope: CoroutineScope,
): DefaultPersistedSessionViewModelRegistry =
    DefaultPersistedSessionViewModelRegistry(
        repositoryFactory = KodexSessionRepositoryFactory { repository },
        scope = scope,
        agentFactory = PersistedSessionAgentViewModelFactory {
                session,
                address,
                parentAddress,
                ownerScope,
                _,
            ->
            createAgentRuntimeViewModel(
                session = session,
                address = address,
                parentAddress = parentAddress,
                ownerScope = ownerScope,
                composerFactory = DefaultComposerViewModelFactory,
                historyFactory = AgentRuntimeHistoryViewModelFactory {
                        agentSession,
                        childScope,
                    ->
                    createAgentHistoryViewModel(agentSession.runtime, childScope)
                },
            )
        },
    )
