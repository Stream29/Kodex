package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.terminal.Terminal
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Text
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.settings.createSessionSettingsViewModel
import io.github.stream29.kodex.app.settings.createSettingsViewModel
import io.github.stream29.kodex.app.settings.contract.*
import io.github.stream29.kodex.app.test.startRpcFrontendFixture
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.cli.rpc.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.*

/** Actual pointer input through the full popup, original root and real RPC-backed children. */
val settingsPopupInputTest by testSuite {
    test("all navigation inputs retain exact children and OpenAI refreshes once per entry") {
        runMosaicTest {
            state.size.value = Terminal.Size(120, 40)
            val renderingScope = CoroutineScope(currentCoroutineContext())
            val fixture = startRpcFrontendFixture(
                renderingScope,
                frontendDispatcher = renderingScope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher,
            )
            try {
                val refreshes = MutableStateFlow(0)
                val rpc = object : io.github.stream29.kodex.rpc.contract.GlobalRpc by fixture.services.global {
                    override suspend fun refreshAccountUsage() { refreshes.value++ }
                }
                val global = RpcGlobalSettings.open(rpc, openCliFrontendSettings(fixture.root), fixture, 120)
                val target = fixture.create("captured Settings target")
                fixture.create("other target")
                val view = fixture.views.open(target.sessionIndex)
                val editor = RpcGlobalEditor(global, rpc, fixture)
                val session = createSessionSettingsViewModel(SessionSettingsDependencies(RpcSessionSettingsSource(view, fixture), fixture.models), fixture)
                val defaults = RpcNewSessionSettings(global, fixture)
                val vm = createSettingsViewModel(SettingsPage.General, editor, session, defaults)
                var visible by mutableStateOf(true)
                var dismissals = 0
                try {
                    var snapshot = setContentAndSnapshot {
                        TuiPopupHost(Modifier.width(120).height(40)) {
                            if (visible) SettingsPopup(
                                vm,
                                onDismissRequest = { dismissals++; vm.close(); visible = false },
                                onOpenLogin = { error("Navigation must not request Login") },
                            ) else Text("Settings owner closed")
                        }
                    }
                    assertTrue("Left sidebar width" in snapshot, snapshot)
                    assertEquals(0, refreshes.value)
                    for (page in listOf(
                        SettingsPage.ContextSources, SettingsPage.OpenAi, SettingsPage.Mcp,
                        SettingsPage.Hooks, SettingsPage.CurrentSession, SettingsPage.NewSession,
                        SettingsPage.General, SettingsPage.OpenAi,
                    )) {
                        clickPopupText(snapshot, "[${page.settingsLabel()}]")
                        snapshot = popupSnapshot()
                        assertEquals(page, vm.selectedPage.value)
                        assertSame(editor, vm.global)
                        assertSame(session, vm.session)
                        assertSame(defaults, vm.newSession)
                        assertFalse(editor.applicationPreferences.state.value.closed)
                        assertFalse(editor.authenticationSettings.state.value.closed)
                        assertTrue(defaults.state.value.active)
                        if (page == SettingsPage.CurrentSession) {
                            assertTrue("captured Settings target" in snapshot, snapshot)
                            assertFalse("other target" in snapshot, snapshot)
                        }
                    }
                    withTimeout(2_000) { refreshes.first { it == 2 } }
                    clickPopupText(snapshot, "[OpenAI]") // Same-page click is a real UI no-op.
                    snapshot = popupSnapshot()
                    assertEquals(2, refreshes.value)
                    assertFalse(editor.accountUsage.state.value.closed)
                    clickPopupText(snapshot, "[Close]")
                    snapshot = popupSnapshot()
                    assertTrue("Settings owner closed" in snapshot, snapshot)
                    assertEquals(1, dismissals)
                    assertTrue(editor.accountUsage.state.value.closed)
                    assertTrue(editor.authenticationSettings.state.value.closed)
                    assertFalse(defaults.state.value.active)
                    assertNotNull(fixture.services.global.getSettings())
                } finally { vm.close(); global.close(); global.join() }
            } finally { fixture.closeAndJoin() }
        }
    }
}

private suspend fun TestMosaic<String>.popupSnapshot(): String =
    try { awaitSnapshot() } catch (_: TimeoutCancellationException) {
        draw().render(AnsiLevel.NONE, supportsKittyUnderlines = false)
    }

private suspend fun TestMosaic<String>.clickPopupText(snapshot: String, label: String) {
    val lines = snapshot.lines()
    val row = lines.indexOfFirst { label in it }
    assertTrue(row >= 0, snapshot)
    val column = lines[row].indexOf(label) + 1
    sendMouseEvent(MouseEvent(column, row, MouseEvent.Type.Press, MouseEvent.Button.Left))
    popupSnapshot()
    sendMouseEvent(MouseEvent(column, row, MouseEvent.Type.Release))
    popupSnapshot()
}
