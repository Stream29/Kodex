package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import io.github.stream29.kodex.app.session.contract.NewSessionViewModelArguments
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.cli.agent.DefaultComposerViewModelFactory
import io.github.stream29.kodex.cli.newsession.DefaultNewSessionViewModelFactory
import io.github.stream29.kodex.cli.session.DefaultPersistedSessionViewModelRegistry
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

internal class SessionViewModelTestFixture private constructor(
    private val repository: InMemoryKodexSessionRepository,
    private val store: DefaultPersistedSessionViewModelRegistry,
) {
    private val drafts = mutableListOf<NewSessionViewModel>()
    private val draftFactory = DefaultNewSessionViewModelFactory(
        store,
        DefaultComposerViewModelFactory,
        MutableStateFlow(emptyList()),
    )

    fun newSession(name: String): NewSessionViewModel =
        draftFactory.create(
            NewSessionViewModelArguments(
                defaultName = name,
                initialSettings = testAgentSettings(name),
            ),
        ).also(drafts::add)

    suspend fun persistedSession(name: String): PersistedSessionViewModel =
        store.create { testAgentSettings(name) }

    suspend fun close() {
        drafts.forEach(NewSessionViewModel::close)
        store.shutdown()
        repository.cancelAndJoin()
    }

    companion object {
        fun create(scope: CoroutineScope): SessionViewModelTestFixture {
            val repository = with(scope) {
                InMemoryKodexSessionRepository(testKodexAgentDependencies())
            }
            return SessionViewModelTestFixture(
                repository = repository,
                store = testSessionViewModelRegistry(repository, scope),
            )
        }
    }
}

private fun testAgentSettings(name: String): KodexAgentSettings =
    KodexAgentSettings(
        model = OpenAiModelId("test-model"),
        threadName = name,
    )
