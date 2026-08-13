package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.app.application.contract.ApplicationNavigationState
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerViewModel
import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import io.github.stream29.kodex.app.session.contract.NewSessionViewModelArguments
import io.github.stream29.kodex.app.session.contract.NewSessionViewModelFactory
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModelRegistry
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogViewModel
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogViewModelFactory
import io.github.stream29.kodex.app.settings.contract.OpenAiLoginViewModelFactory
import io.github.stream29.kodex.app.settings.contract.SettingsViewModelFactory
import io.github.stream29.kodex.cli.agent.DefaultComposerViewModelFactory
import io.github.stream29.kodex.cli.newsession.DefaultNewSessionViewModelFactory
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

val sessionTabRegistryStateTest by testSuite {
    test("navigation preserves ordered child handles and selected index") {
        coroutineScope {
            val fixture = applicationFixture()
            try {
                val first = assertIs<NewSessionViewModel>(fixture.viewModel.navigation.value.selected)
                val second = fixture.viewModel.createNewSessionTab()

                assertEquals(
                    ApplicationNavigationState(listOf(first, second), selectedIndex = 1),
                    fixture.viewModel.navigation.value,
                )
                assertEquals(true, fixture.viewModel.selectTab(0))
                assertSame(first, fixture.viewModel.navigation.value.selected)

                assertEquals(true, fixture.viewModel.closeTab(first))
                assertEquals(listOf(second), fixture.viewModel.navigation.value.tabs)
                assertEquals(0, fixture.viewModel.navigation.value.selectedIndex)
            } finally {
                fixture.close()
            }
        }
    }

    test("closing the final tab creates a replacement draft") {
        coroutineScope {
            val fixture = applicationFixture()
            try {
                val original = fixture.viewModel.navigation.value.selected

                assertEquals(true, fixture.viewModel.closeTab(original))

                val replacement = fixture.viewModel.navigation.value.selected
                assertIs<NewSessionViewModel>(replacement)
                assertEquals("New Session 2", replacement.name.value)
            } finally {
                fixture.close()
            }
        }
    }
}

internal data class ApplicationTestFixture(
    val repository: InMemoryKodexSessionRepository,
    val sessions: PersistedSessionViewModelRegistry,
    val viewModel: ApplicationViewModelImpl,
) {
    suspend fun close() {
        viewModel.shutdown()
        repository.cancelAndJoin()
    }
}

internal suspend fun kotlinx.coroutines.CoroutineScope.applicationFixture(
    newSessionFactory: NewSessionViewModelFactory? = null,
    createDirectoryPicker: (Path) -> DirectoryPickerViewModel = {
        error("Directory picker is not used by this fixture.")
    },
): ApplicationTestFixture {
    val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
    val sessions = testSessionViewModelRegistry(repository, this)
    val drafts = newSessionFactory ?: DefaultNewSessionViewModelFactory(
        sessions,
        DefaultComposerViewModelFactory,
        MutableStateFlow(emptyList()),
    )
    val viewModel = ApplicationViewModelImpl(
        sessions = sessions,
        newSessionFactory = drafts,
        catalogFactory = SessionCatalogViewModelFactory { EmptySessionCatalogViewModel() },
        settingsFactory = SettingsViewModelFactory {
            error("Settings are not used by this fixture.")
        },
        loginFactory = OpenAiLoginViewModelFactory {
            error("Login is not used by this fixture.")
        },
        createDirectoryPicker = createDirectoryPicker,
        newSessionArguments = { ordinal ->
            NewSessionViewModelArguments(
                defaultName = if (ordinal == 1) "New Session" else "New Session $ordinal",
                initialSettings = KodexAgentSettings(
                    model = OpenAiModelId("test-model"),
                    cwd = Path("."),
                ),
            )
        },
    )
    return ApplicationTestFixture(repository, sessions, viewModel)
}

private class EmptySessionCatalogViewModel : SessionCatalogViewModel {
    override val sessions: StateFlow<List<SessionCatalogEntry>> = MutableStateFlow(emptyList())

    override suspend fun refresh(): Unit = Unit

    override fun close(): Unit = Unit
}
