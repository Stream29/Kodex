package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.contract.initialize
import io.github.stream29.kodex.app.application.contract.ApplicationPopupState
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerEffect
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerLoadState
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerState
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerViewModel
import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.io.files.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

val sessionTreeCliViewModelTest by testSuite {
    test("materialization replaces the exact draft index atomically") {
        coroutineScope {
            val fixture = applicationFixture()
            try {
                val first = assertIs<NewSessionViewModel>(fixture.viewModel.navigation.value.selected)
                fixture.viewModel.createNewSessionTab()
                val before = fixture.viewModel.navigation.value

                val persisted = fixture.viewModel.materializeNewSession(tabIndex = 1)

                val after = fixture.viewModel.navigation.value
                assertEquals(before.tabs.size, after.tabs.size)
                assertSame(first, after.tabs[0])
                assertSame(persisted, after.tabs[1])
                assertEquals(before.selectedIndex, after.selectedIndex)
                assertIs<PersistedSessionViewModel>(after.selected)
            } finally {
                fixture.close()
            }
        }
    }

    test("opening an existing persisted session reuses its tab") {
        coroutineScope {
            val fixture = applicationFixture()
            val index = fixture.repository.create()
            fixture.repository.open(index).runtime.modify { storage ->
                storage.initialize(
                    KodexAgentSettings(
                        model = OpenAiModelId("test-model"),
                        threadName = "Existing",
                    ),
                )
            }
            try {
                val first = fixture.viewModel.openSession(index)
                val count = fixture.viewModel.navigation.value.tabs.size

                val second = fixture.viewModel.openSession(index)

                assertSame(first, second)
                assertEquals(count, fixture.viewModel.navigation.value.tabs.size)
                assertSame(second, fixture.viewModel.navigation.value.selected)
            } finally {
                fixture.close()
            }
        }
    }

    test("popup dismissal requires the exact still-current handle") {
        coroutineScope {
            val fixture = applicationFixture()
            try {
                val catalog = fixture.viewModel.openSessionCatalogPopup()
                val replacement = fixture.viewModel.openSessionCatalogPopup()

                assertFalse(fixture.viewModel.dismissPopup(catalog))
                assertSame(replacement, fixture.viewModel.popup.value)
                assertTrue(fixture.viewModel.dismissPopup(replacement))
                assertIs<ApplicationPopupState.Closed>(fixture.viewModel.popup.value)
            } finally {
                fixture.close()
            }
        }
    }

    test("working-directory popup updates only its captured settings owner") {
        coroutineScope {
            lateinit var picker: TestDirectoryPickerViewModel
            val fixture = applicationFixture { initialDirectory ->
                TestDirectoryPickerViewModel(initialDirectory).also { picker = it }
            }
            try {
                val first = assertIs<NewSessionViewModel>(
                    fixture.viewModel.navigation.value.selected,
                )
                val open = fixture.viewModel.openWorkingDirectoryPopup(first)
                val second = fixture.viewModel.createNewSessionTab()
                val selectedDirectory = Path("captured-directory")

                open.viewModel.select(selectedDirectory)

                assertSame(first, open.viewModel.target)
                assertEquals(selectedDirectory, first.settings.value.cwd)
                assertEquals(Path("."), second.settings.value.cwd)
                assertTrue(picker.closed)
                assertTrue(fixture.viewModel.closeTab(first))
                assertIs<ApplicationPopupState.Closed>(fixture.viewModel.popup.value)
            } finally {
                fixture.close()
            }
        }
    }

    test("child-owned changes do not republish application state") {
        coroutineScope {
            val fixture = applicationFixture()
            val index = fixture.repository.create()
            fixture.repository.open(index).runtime.modify { storage ->
                storage.initialize(
                    KodexAgentSettings(
                        model = OpenAiModelId("test-model"),
                        threadName = "Child state",
                    ),
                )
            }
            val persisted = fixture.viewModel.openSession(index)
            val navigationEmissions = MutableStateFlow(0)
            val popupEmissions = MutableStateFlow(0)
            val collectors = listOf(
                launch(start = CoroutineStart.UNDISPATCHED) {
                    fixture.viewModel.navigation.collect {
                        navigationEmissions.update { count -> count + 1 }
                    }
                },
                launch(start = CoroutineStart.UNDISPATCHED) {
                    fixture.viewModel.popup.collect {
                        popupEmissions.update { count -> count + 1 }
                    }
                },
            )
            try {
                val navigation = fixture.viewModel.navigation.value
                val popup = fixture.viewModel.popup.value

                repeat(128) { revision ->
                    val text = "draft-$revision"
                    persisted.rootAgent.composer.update(text, text.length)
                }
                persisted.rename("Renamed child")
                yield()

                assertEquals(128, persisted.rootAgent.composer.state.value.revision)
                assertEquals("Renamed child", persisted.name.value)
                assertSame(navigation, fixture.viewModel.navigation.value)
                assertSame(popup, fixture.viewModel.popup.value)
                assertEquals(1, navigationEmissions.value)
                assertEquals(1, popupEmissions.value)
            } finally {
                collectors.forEach(Job::cancel)
                collectors.forEach { collector -> collector.join() }
                fixture.close()
            }
        }
    }
}

private class TestDirectoryPickerViewModel(
    initialDirectory: Path,
) : DirectoryPickerViewModel {
    override val state: StateFlow<DirectoryPickerState> = MutableStateFlow(
        DirectoryPickerState(
            loadState = DirectoryPickerLoadState.Ready(
                requestId = 1,
                requestedDirectory = initialDirectory,
                directory = initialDirectory,
                children = emptyList(),
            ),
        ),
    )
    override val effects: Flow<DirectoryPickerEffect> = emptyFlow()
    var closed: Boolean = false
        private set

    override fun navigateTo(directory: Path): Unit = Unit

    override fun navigateUp(): Unit = Unit

    override fun updateFilter(query: String): Unit = Unit

    override fun clearFilter(): Unit = Unit

    override fun retry(): Unit = Unit

    override fun confirm(): Unit = Unit

    override fun close() {
        closed = true
    }
}
