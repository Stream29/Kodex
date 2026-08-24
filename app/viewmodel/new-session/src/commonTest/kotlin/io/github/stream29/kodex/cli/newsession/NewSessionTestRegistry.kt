package io.github.stream29.kodex.cli.newsession

import io.github.stream29.kodex.agentsession.contract.KodexSessionRepository
import io.github.stream29.kodex.cli.agent.AgentAutomaticTitleConfiguration
import io.github.stream29.kodex.cli.agent.DefaultComposerViewModelFactory
import io.github.stream29.kodex.cli.agent.createAgentRuntimeViewModel
import io.github.stream29.kodex.cli.history.createAgentHistoryViewModel
import io.github.stream29.kodex.cli.session.DefaultPersistedSessionViewModelRegistry
import io.github.stream29.kodex.cli.session.KodexSessionRepositoryFactory
import kotlinx.coroutines.CoroutineScope

internal fun testSessionViewModelRegistry(
    repository: KodexSessionRepository,
    scope: CoroutineScope,
    automaticTitleConfiguration: AgentAutomaticTitleConfiguration? = null,
): DefaultPersistedSessionViewModelRegistry =
    DefaultPersistedSessionViewModelRegistry(
        repositoryFactory = KodexSessionRepositoryFactory { repository },
        scope = scope,
        agentFactory = {
                session,
                address,
                parentAddress,
                ownerScope,
                isRoot,
            ->
            createAgentRuntimeViewModel(
                session = session,
                address = address,
                parentAddress = parentAddress,
                ownerScope = ownerScope,
                composerFactory = DefaultComposerViewModelFactory,
                historyFactory = {
                        agentSession,
                        childScope,
                    ->
                    createAgentHistoryViewModel(agentSession.runtime, childScope)
                },
                automaticTitleConfiguration = automaticTitleConfiguration.takeIf { isRoot },
            )
        },
    )
