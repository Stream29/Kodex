package io.github.stream29.kodex.cli.app

import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Text
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.cli.history.AgentHistoryViewState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

val historyRenderStateTest by testSuite {
    test("root observes hidden binding withdrawal and prunes only its renderer state") {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val selected = fixture.persistedSession("Selected")
            val hidden = fixture.persistedSession("Hidden")
            val hiddenAgent = MutableStateFlow(hidden.rootAgent.value)
            val hiddenTab = object : PersistedSessionViewModel by hidden {
                override val rootAgent = hiddenAgent
            }
            val selectedHistory = requireNotNull(selected.rootAgent.value).history
            val hiddenHistory = requireNotNull(hidden.rootAgent.value).history
            var retained: MutableList<Pair<AgentHistoryViewModel, AgentHistoryViewState>>? = null
            runMosaicTest {
                setContentAndSnapshot {
                    val states = rememberHistoryRenderStates(listOf(selected, hiddenTab))
                    retained = states
                    historyRenderStateFor(states, selectedHistory)
                    if (hiddenAgent.value != null) historyRenderStateFor(states, hiddenHistory)
                    Text("retained=${states.size}")
                }
                val states = requireNotNull(retained)
                val selectedState = historyRenderStateFor(states, selectedHistory)
                assertEquals(2, states.size)
                hiddenAgent.value = null
                assertTrue("retained=1" in awaitSnapshot())
                assertSame(selectedState, states.single().second)
                assertSame(selectedHistory, states.single().first)
            }
        } finally {
            fixture.close()
        }
    }

    test("tab renderer state follows exact History ownership, not equality or storage identity") {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val history = requireNotNull(fixture.persistedSession("History").rootAgent.value).history
            // An equal-valued implementation must never acquire another owner's widget state.
            val first = EqualHistoryOwner(history)
            val second = EqualHistoryOwner(history)
            val states = mutableListOf<Pair<AgentHistoryViewModel, AgentHistoryViewState>>()
            val firstState = historyRenderStateFor(states, first)
            val secondState = historyRenderStateFor(states, second)
            assertNotSame(firstState, secondState)
            assertSame(firstState, historyRenderStateFor(states, first))
            assertEquals(2, states.size)

            // Selecting the other tab does not prune a still-owned, unmounted History.
            pruneHistoryRenderStates(states, listOf(second, first))
            assertSame(firstState, historyRenderStateFor(states, first))

            // A closed/replaced owner has no retained renderer state.
            pruneHistoryRenderStates(states, listOf(second))
            assertEquals(1, states.size)
            assertSame(secondState, states.single().second)
            assertNotSame(firstState, historyRenderStateFor(states, first))
            pruneHistoryRenderStates(states, emptyList())
            assertTrue(states.isEmpty())
        } finally {
            fixture.close()
        }
    }
}

private class EqualHistoryOwner(
    history: AgentHistoryViewModel,
) : AgentHistoryViewModel by history {
    override fun equals(other: Any?): Boolean = other is EqualHistoryOwner
    override fun hashCode(): Int = 0
}
