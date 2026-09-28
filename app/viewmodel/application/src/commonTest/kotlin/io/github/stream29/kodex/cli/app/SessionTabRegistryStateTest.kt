package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.application.contract.ApplicationNavigationState
import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import kotlin.test.*

val sessionTabRegistryStateTest by testSuite {
    test("navigation preserves ordered child handles and selected index") {
        applicationFixture { app, _ ->
            val vm = app.viewModel
            val first = assertIs<NewSessionViewModel>(vm.navigation.value.selected)
            val second = vm.createNewSessionTab()
            assertEquals(ApplicationNavigationState(listOf(first, second), 1), vm.navigation.value)
            assertTrue(vm.selectTab(0))
            assertSame(first, vm.navigation.value.selected)
            assertTrue(vm.closeTab(first))
            assertEquals(listOf(second), vm.navigation.value.tabs)
            assertEquals(0, vm.navigation.value.selectedIndex)
        }
    }
    test("closing the final tab creates a replacement draft") {
        applicationFixture { app, _ ->
            val vm = app.viewModel
            assertTrue(vm.closeTab(vm.navigation.value.selected))
            val next = assertIs<NewSessionViewModel>(vm.navigation.value.selected)
            assertEquals("New Session 2", next.name.value)
        }
    }
}
