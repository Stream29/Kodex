@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.agent.contract.AgentShellSession
import io.github.stream29.kodex.cli.historyindex.*
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

val applicationOverlaysTest by testSuite {
    test("row to popup bridge keeps the same hover and closes exactly at grace expiry") { runTest {
        val state = ApplicationOverlayStateImpl(this)
        val origin = OverlayShell()
        val hover = ApplicationOverlayHover.Shell(SessionSidebarSide.Left, origin)
        state.hoverChanged(hover, true)
        state.hoverChanged(hover, false)
        runCurrent()
        advanceTimeBy(179)
        runCurrent()
        assertSame(hover, state.hover)
        state.popupHoverChanged(hover, true)
        advanceTimeBy(10_000)
        runCurrent()
        assertSame(hover, state.hover)
        state.popupHoverChanged(hover, false)
        runCurrent()
        advanceTimeBy(180)
        runCurrent()
        assertNull(state.hover)
        assertEquals(0, origin.session.closes)
    } }
    test("one menu clears hover and stale dismissals cannot clear another opening or side") { runTest {
        val state = ApplicationOverlayStateImpl(this)
        val origin = OverlayShell()
        val hover = ApplicationOverlayHover.Shell(SessionSidebarSide.Left, origin)
        state.hoverChanged(hover, true)
        val first = ApplicationOverlayMenu.Shell(SessionSidebarSide.Left, SidebarShellMenuOpening(origin))
        state.openMenu(first)
        assertNull(state.hover)
        state.hoverChanged(hover, true)
        assertNull(state.hover)
        val second = ApplicationOverlayMenu.Index(SessionSidebarSide.Right, HistoryIndexMenuOpening(OverlayIndex))
        state.openMenu(second)
        assertFalse(state.dismissMenu(first))
        state.clearSide(SessionSidebarSide.Left)
        assertSame(second, state.menu)
        assertTrue(state.dismissMenu(second))
        assertNull(state.menu)
        assertEquals(0, origin.session.closes)
    } }
    test("row identity preserves reads but neither equal rows nor old timers can retire a replacement") { runTest {
        data class EqualRow(val value: Int)
        val state = ApplicationOverlayStateImpl(this)
        val row = EqualRow(1)
        val first = ApplicationOverlayHover.Shell(SessionSidebarSide.Left, OverlayShell(), row)
        state.hoverChanged(first, true)
        state.hoverChanged(ApplicationOverlayHover.Shell(SessionSidebarSide.Left, OverlayShell(), row), true)
        assertSame(first, state.hover)
        val equalRow = ApplicationOverlayHover.Shell(SessionSidebarSide.Left, OverlayShell(), EqualRow(1))
        state.hoverChanged(equalRow, true)
        assertSame(equalRow, state.hover)
        state.hoverChanged(equalRow, false)
        runCurrent()
        advanceTimeBy(100)
        val replacement = ApplicationOverlayHover.Index(SessionSidebarSide.Right, OverlayIndex, EqualRow(1))
        state.hoverChanged(replacement, true)
        state.hoverChanged(first, false)
        state.popupHoverChanged(first, false)
        advanceTimeBy(1_000)
        runCurrent()
        assertSame(replacement, state.hover)
        assertFalse(state.dismissHover(first))
        assertFalse(state.dismissHover(equalRow))
        state.clearSide(SessionSidebarSide.Left)
        assertSame(replacement, state.hover)
        state.clearSidebar()
        assertNull(state.hover)
    } }
}

private class OverlayShell : SidebarShellInteraction {
    override val session = OverlayProcess()
    override val isPlaced = true
}

private class OverlayProcess : AgentShellSession {
    override val sessionId = 1
    override val arguments = ExecCommandArguments("test", yieldTimeMillis = 10_000)
    override val completed = MutableStateFlow(false)
    var closes = 0
    override fun close() { closes++ }
}

private object OverlayIndex : HistoryIndexInteraction {
    override val target: HistoryIndexTarget get() = error("Arbitration must not read a business child")
    override val isPlaced = true
}
