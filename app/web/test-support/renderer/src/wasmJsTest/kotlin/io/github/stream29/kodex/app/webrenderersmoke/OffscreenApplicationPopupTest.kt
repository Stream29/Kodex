package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.*
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.application.contract.*
import io.github.stream29.kodex.app.mcpsettings.McpSettingsDialog
import io.github.stream29.kodex.app.settings.contract.*
import io.github.stream29.kodex.app.ui.compose.rememberComposeApplicationPopupRenderer
import io.github.stream29.kodex.cli.app.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.test.*

val offscreenApplicationPopupTest by testSuite {
    for (unmountDuringUrl in listOf(false, true)) {
        test("shared popup keeps exact MCP consumer across real Settings Login return unmount=$unmountDuringUrl") { settingsRootScene { fixture ->
            val settings = ApplicationPopupState.Settings(null, fixture.root)
            val app = PopupApplication(settings)
            val gate = CompletableDeferred<Unit>()
            var opens = 0
            var cancelled = 0
            val failures = mutableListOf<Throwable>()
            val scene = OffscreenScene.create {
                val popup = app.popup.collectAsState().value
                ApplicationPopupContent(
                    app, popup, rememberComposeApplicationPopupRenderer { false }, failures::add,
                    openMcpUrl = {
                        opens++
                        try { gate.await(); true }
                        catch (failure: CancellationException) { cancelled++; throw failure }
                    },
                )
            }
            var closed = false
            try {
                scene.settle { scene.hasText("Input") }
                fixture.global.mcpSettings.details("server")
                scene.settle { fixture.global.mcpSettings.state.value.dialog is McpSettingsDialog.Details }
                val token = (fixture.global.mcpSettings.state.value.dialog as McpSettingsDialog.Details).token
                fixture.global.mcpSettings.login(token)
                scene.settle { opens == 1 }
                fixture.global.intents.send(GlobalSettingsEffect.OpenLogin)
                scene.settle { scene.hasText("Sign in to OpenAI") }
                assertSame(settings, app.openedFrom.single())
                assertSame(settings, assertIs<ApplicationPopupState.Login>(app.popup.value).returnTo)
                assertEquals(0, cancelled)
                assertEquals(0, fixture.global.closes)
                if (unmountDuringUrl) {
                    scene.close(); closed = true
                    withTimeout(5_000) {
                        while (cancelled != 1 || fixture.global.mcpPorts.logins.single().cancellations != 1) delay(1)
                    }
                    assertEquals(1, cancelled)
                    assertEquals(1, fixture.global.mcpPorts.logins.single().cancellations)
                    assertTrue(app.logins.single().closed)
                } else {
                    scene.click("Cancel")
                    scene.settle { app.popup.value === settings && scene.hasText("Input") }
                    assertTrue(app.logins.single().closed)
                    assertEquals(1, app.logins.single().cancellations)
                    assertEquals(0, cancelled)
                    gate.complete(Unit)
                    scene.settle { true }
                    assertEquals(1, opens)
                    assertEquals(0, fixture.global.mcpPorts.logins.single().cancellations)
                }
                assertTrue(failures.isEmpty())
                assertEquals(0, fixture.global.closes)
            } finally { if (!closed) scene.close() }
        } }
    }
    test("stale Settings close and login callbacks retain exact open handle and report failures once") { settingsRootScene { fixture ->
        val first = ApplicationPopupState.Settings(null, fixture.root)
        val second = ApplicationPopupState.Settings(null, fixture.root)
        val app = PopupApplication(first)
        val closes = mutableListOf<() -> Unit>()
        val logins = mutableListOf<() -> Unit>()
        val seen = mutableListOf<ApplicationPopupState>()
        val failures = mutableListOf<Throwable>()
        val scene = OffscreenScene.create {
            val popup = app.popup.collectAsState().value
            val graphical = rememberComposeApplicationPopupRenderer { false }
            val renderer = object : ApplicationPopupRenderer by graphical {
                @Composable
                override fun Settings(viewModel: SettingsViewModel, onClose: () -> Unit, onLogin: () -> Unit) {
                    if (seen.none { it === popup }) { seen += popup; closes += onClose; logins += onLogin }
                    graphical.Settings(viewModel, onClose, onLogin)
                }
            }
            ApplicationPopupContent(app, popup, renderer, failures::add, { true })
        }
        try {
            scene.settle { closes.size == 1 }
            app.popup.value = second
            scene.settle { closes.size == 2 }
            closes.first()()
            assertSame(first, app.dismissed.single())
            assertSame(second, app.popup.value)
            logins.first()()
            scene.settle { failures.size == 1 }
            assertSame(second, app.popup.value)
            app.openingFailure = CancellationException("wait cancelled")
            logins.last()()
            scene.settle { app.openedFrom.size == 2 }
            scene.advance()
            assertEquals(1, failures.size)
            val failure = IllegalStateException("explicit login opening failure")
            app.openingFailure = failure
            logins.last()()
            scene.settle { failures.size == 2 }
            assertSame(failure, failures.last())
            assertSame(second, app.popup.value)
            app.openingFailure = null
            logins.last()()
            scene.settle { scene.hasText("Sign in to OpenAI") }
            assertSame(second, app.openedFrom.last())
            scene.frame()
        } finally { scene.close() }
        assertEquals(0, fixture.global.closes)
    } }
}

/** Exact-handle intent probe; actual Application admission and cleanup also run in Native root tests. */
private class PopupApplication(initial: ApplicationPopupState) : ApplicationViewModel by NavigationApplication(emptyList()) {
    override val popup = kotlinx.coroutines.flow.MutableStateFlow(initial)
    val openedFrom = mutableListOf<ApplicationPopupState.Settings>()
    val dismissed = mutableListOf<ApplicationPopupState.Open>()
    val logins = mutableListOf<LoginRenderChild>()
    var openingFailure: Throwable? = null
    override suspend fun openLoginPopup(returnTo: ApplicationPopupState.Settings): ApplicationPopupState.Login {
        openedFrom += returnTo
        openingFailure?.let { throw it }
        check(popup.value === returnTo) { "Settings opening is no longer current" }
        val child = LoginRenderChild(OpenAiLoginState.Ready).also(logins::add)
        return ApplicationPopupState.Login(child, returnTo).also { popup.value = it }
    }
    override fun dismissPopup(expected: ApplicationPopupState.Open): Boolean {
        dismissed += expected
        if (popup.value !== expected) return false
        popup.value = if (expected is ApplicationPopupState.Login) expected.returnTo else ApplicationPopupState.Closed
        return true
    }
}
