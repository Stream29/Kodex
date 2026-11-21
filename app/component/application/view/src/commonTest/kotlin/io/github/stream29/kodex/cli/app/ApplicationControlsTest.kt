package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.application.contract.SidebarSettingsViewModel
import io.github.stream29.kodex.cli.settings.SidebarSettings
import io.github.stream29.kodex.cli.settings.ToolWindowId
import io.github.stream29.kodex.cli.settings.ToolWindowSide
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

val applicationControlsTest by testSuite {
    test("sidebar controls route the exact side and logical value without touching the viewport") { runTest {
        val owner = SidebarControlProbe()
        owner.collapse(ToolWindowSide.Left)
        owner.toggle(ToolWindowId.HistoryIndex)
        resizeApplicationSidebar(owner, SessionSidebarSide.Left, 12)
        resizeApplicationSidebar(owner, SessionSidebarSide.Right, 24)
        assertEquals(listOf("collapse=Left", "toggle=HistoryIndex", "left-width=12", "right-width=24"), owner.calls)
    } }
    test("sidebar failure and cancellation are propagated once without retries or fallback writes") { runTest {
        val owner = SidebarControlProbe()
        val failure = IllegalStateException("preference write failed")
        owner.failure = failure
        assertSame(failure, assertFailsWith<IllegalStateException> {
            owner.toggle(ToolWindowId.TerminalSessions)
        })
        val cancelled = CancellationException("caller stopped waiting")
        owner.failure = cancelled
        assertSame(cancelled, assertFailsWith<CancellationException> {
            resizeApplicationSidebar(owner, SessionSidebarSide.Right, 18)
        })
        assertEquals(listOf("toggle=TerminalSessions", "right-width=18"), owner.calls)
    } }
}

private class SidebarControlProbe : SidebarSettingsViewModel {
    override val state = MutableStateFlow(SidebarSettings())
    val calls = mutableListOf<String>()
    var failure: Throwable? = null
    override fun initializeViewport(columns: Int) { error("A control must not reinitialize its owner") }
    private fun accept(call: String) { calls += call; failure?.let { throw it } }
    override suspend fun toggle(id: ToolWindowId) { accept("toggle=$id") }
    override suspend fun collapse(side: ToolWindowSide) { accept("collapse=$side") }
    override suspend fun resizeLeft(columns: Int) { accept("left-width=$columns") }
    override suspend fun resizeRight(columns: Int) { accept("right-width=$columns") }
}
