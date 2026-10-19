package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.application.contract.ApplicationNavigationState
import io.github.stream29.kodex.app.application.contract.ApplicationTabSelection
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import kotlin.test.*

val sessionTabRegistryStateTest by testSuite {
    test("empty navigation selects New Session and rejects invalid indexes") {
        val empty = ApplicationNavigationState(emptyList(), ApplicationTabSelection.NewSession)
        assertNull(empty.selectedSession)
        for (index in listOf(-1, 0, 1)) {
            assertFailsWith<IllegalArgumentException> {
                ApplicationNavigationState(emptyList(), ApplicationTabSelection.SelectedTab(index))
            }
        }
    }
    test("navigation validates referential duplicates rather than structural equality") {
        applicationFixture { app, _ ->
            val real = app.viewModel.submitTestSession()
            val first = EqualityEqualSession(real)
            val second = EqualityEqualSession(real)
            assertEquals(first, second)
            val state = ApplicationNavigationState(listOf(first, second), ApplicationTabSelection.SelectedTab(1))
            assertSame(second, state.selectedSession)
            assertFailsWith<IllegalArgumentException> {
                ApplicationNavigationState(listOf(first, first), ApplicationTabSelection.NewSession)
            }
            for (index in listOf(-1, 2)) {
                assertFailsWith<IllegalArgumentException> {
                    state.copy(selection = ApplicationTabSelection.SelectedTab(index))
                }
            }
        }
    }
    test("closing preceding and following tabs retains the selected instance then clamps a removed selection") {
        applicationFixture { app, _ ->
            val vm = app.viewModel
            val singleton = vm.newSession
            val first = vm.submitTestSession()
            val selected = vm.submitTestSession()
            val following = vm.submitTestSession()
            val last = vm.submitTestSession()
            vm.selectTab(selected)
            assertTrue(vm.closeTab(first))
            assertSame(selected, vm.navigation.value.selectedSession)
            assertEquals(ApplicationTabSelection.SelectedTab(0), vm.navigation.value.selection)
            assertTrue(vm.closeTab(last))
            assertSame(selected, vm.navigation.value.selectedSession)
            assertTrue(vm.closeTab(selected))
            assertSame(following, vm.navigation.value.selectedSession)
            assertTrue(vm.closeTab(following))
            assertEquals(emptyList(), vm.navigation.value.tabs)
            assertEquals(ApplicationTabSelection.NewSession, vm.navigation.value.selection)
            assertSame(singleton, vm.newSession)
        }
    }
    test("closing background tabs on New Session preserves selection and draft") {
        applicationFixture { app, _ ->
            val vm = app.viewModel
            val tab = vm.submitTestSession()
            vm.selectNewSession()
            vm.newSession.composer.update("unsent", 6)
            val input = vm.newSession.composer.state.value
            assertTrue(vm.closeTab(tab))
            assertEquals(ApplicationTabSelection.NewSession, vm.navigation.value.selection)
            assertEquals(input, vm.newSession.composer.state.value)
        }
    }
}

private class EqualityEqualSession(
    delegate: PersistedSessionViewModel,
) : PersistedSessionViewModel by delegate {
    override fun equals(other: Any?): Boolean = other is EqualityEqualSession
    override fun hashCode(): Int = 0
}
