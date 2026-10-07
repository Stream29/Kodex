package io.github.stream29.kodex.cli.authenticationsettings

import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Column
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.authenticationsettings.AuthenticationSettingsDependencies
import io.github.stream29.kodex.app.authenticationsettings.AuthenticationSettingsState
import io.github.stream29.kodex.app.authenticationsettings.createAuthenticationSettingsViewModel
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationOperation
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationOperationState
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationState
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.cli.components.rememberTuiDropdownState
import io.github.stream29.kodex.cli.settings.KodexAuthSource
import io.github.stream29.kodex.openai.OpenAiAuthState
import io.github.stream29.kodex.openai.OpenAiSubscriptionPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

val authenticationSettingsComponentTest by testSuite {
    val reasons = listOf(
        OpenAiAuthState.Unavailable.NotLoaded to "have not been loaded",
        OpenAiAuthState.Unavailable.CredentialsNotFound to "No credentials were found",
        OpenAiAuthState.Unavailable.UnsupportedAuthMode to "unsupported authentication mode",
        OpenAiAuthState.Unavailable.InvalidCredentials to "malformed or incomplete",
        OpenAiAuthState.Unavailable.CredentialSourceUnavailable to "could not be read",
        OpenAiAuthState.Unavailable.UnexpectedFailure to "unexpected internal error",
    )
    for ((reason, text) in reasons) {
        test("safe unavailable renderer $reason") {
            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    Column(Modifier.width(120)) {
                        AuthenticationSettingsContent(
                            AuthenticationSettingsState(KodexAuthSource.Codex, SettingsAuthenticationState.Unavailable(reason)),
                            rememberTuiDropdownState(), {}, {}, {},
                        )
                    }
                }
                assertTrue(text in snapshot, snapshot)
                assertTrue("Codex credentials" in snapshot, snapshot)
                assertTrue("[Sign in]" in snapshot, snapshot)
                assertFalse("[Log out]" in snapshot, snapshot)
                assertFalse("Reload" in snapshot, snapshot)
            }
        }
    }
    for ((auth, identity) in listOf(
        SettingsAuthenticationState.Authenticated(
            email = "safe@example.test", accountId = "hidden-account", planType = OpenAiSubscriptionPlan.Pro,
        ) to "Signed in as safe@example.test",
        SettingsAuthenticationState.Authenticated(accountId = "account") to "Signed in as account account",
        SettingsAuthenticationState.Authenticated() to "Signed in",
    )) {
        test("authenticated identity fallback $identity and relogin logout entry") {
            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    Column(Modifier.width(120)) {
                        AuthenticationSettingsContent(
                            AuthenticationSettingsState(KodexAuthSource.Kodex, auth),
                            rememberTuiDropdownState(), {}, {}, {},
                        )
                    }
                }
                assertTrue(identity in snapshot, snapshot)
                assertTrue("[Sign in again]" in snapshot, snapshot)
                assertTrue("[Log out]" in snapshot, snapshot)
                assertTrue("Maintained by the backend" in snapshot, snapshot)
                assertFalse("hidden-account" in snapshot, snapshot)
                if (auth.planType != null) assertTrue("Plan: pro" in snapshot, snapshot)
            }
        }
    }
    test("live source confirmation rerenders then keyboard confirm removes the command-start source") {
        val ports = RendererAuthPorts(KodexAuthSource.Kodex)
        val owner = Job()
        val vm = createAuthenticationSettingsViewModel(ports, CoroutineScope(Dispatchers.Unconfined + owner))
        vm.requestLogout()
        val expected = assertNotNull(vm.state.value.confirmation)
        try {
            runMosaicTest {
                setContentAndSnapshot {
                    Box {
                        TuiPopupHost(Modifier.width(120).height(28)) {
                            AuthenticationSettingsComponent(vm)
                        }
                    }
                }
                ports.selectedSource.value = KodexAuthSource.Codex
                val switched = awaitSnapshot()
                assertSame(expected, vm.state.value.confirmation)
                assertTrue("~/.codex/auth.json" in switched, switched)
                assertFalse("Remove Kodex private credentials?" in switched, switched)
                sendKeyEvent(KeyboardEvent(codepoint = 9)) // Cancel -> destructive confirmation.
                sendKeyEvent(KeyboardEvent(codepoint = 13))
                awaitSnapshot()
                assertEquals(listOf(KodexAuthSource.Codex), ports.removed)
                assertNull(vm.state.value.confirmation)
                vm.confirmLogout(expected)
                vm.cancelLogout(expected)
                assertEquals(1, ports.removed.size)
            }
        } finally { vm.close(); owner.cancel() }
    }
    test("SigningOut renders progress and disabled keyboard controls cannot emit intents") {
        var calls = 0
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Column(Modifier.width(120)) {
                    AuthenticationSettingsContent(
                        AuthenticationSettingsState(KodexAuthSource.Kodex, SettingsAuthenticationState.Authenticated(),
                            operation = SettingsAuthenticationOperationState.SigningOut),
                        rememberTuiDropdownState(), { calls++ }, { calls++ }, { calls++ },
                    )
                }
            }
            assertTrue("Signing out…" in snapshot, snapshot)
            repeat(4) {
                sendKeyEvent(KeyboardEvent(codepoint = 9))
                sendKeyEvent(KeyboardEvent(codepoint = 13))
            }
            assertEquals(0, calls)
        }
    }
    test("failed operation is safe and dismissible without asserting credentials were kept") {
        var dismissals = 0
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Column(Modifier.width(120)) {
                    AuthenticationSettingsContent(
                        AuthenticationSettingsState(KodexAuthSource.Kodex, SettingsAuthenticationState.Authenticated(),
                            operation = SettingsAuthenticationOperationState.Failed(SettingsAuthenticationOperation.Logout),
                            operationFailure = true),
                        rememberTuiDropdownState(), {}, {}, { dismissals++ },
                    )
                }
            }
            assertTrue("Could not log out." in snapshot, snapshot)
            assertFalse("credentials were kept" in snapshot, snapshot)
            assertFalse("A settings operation failed." in snapshot, snapshot)
            repeat(3) {
                sendKeyEvent(KeyboardEvent(codepoint = 9))
                flushAuthenticationInput()
            }
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            flushAuthenticationInput()
            assertEquals(1, dismissals)
        }
    }
    for (source in KodexAuthSource.entries) {
        test("$source confirmation names exact local target and Cancel does not remove") {
            val ports = RendererAuthPorts(source)
            val owner = Job()
            val vm = createAuthenticationSettingsViewModel(ports, CoroutineScope(Dispatchers.Unconfined + owner))
            vm.requestLogout()
            try {
                runMosaicTest {
                    val snapshot = setContentAndSnapshot {
                        Box {
                            TuiPopupHost(Modifier.width(120).height(28)) {
                                AuthenticationSettingsComponent(vm)
                            }
                        }
                    }
                    if (source == KodexAuthSource.Codex) {
                        assertTrue("~/.codex/auth.json" in snapshot, snapshot)
                        assertTrue("Kodex private credentials are not affected" in snapshot, snapshot)
                    } else {
                        assertTrue("Remove Kodex private credentials?" in snapshot, snapshot)
                        assertTrue("Codex CLI credentials are not affected" in snapshot, snapshot)
                    }
                    assertTrue("does not revoke access remotely" in snapshot, snapshot)
                    sendKeyEvent(KeyboardEvent(codepoint = 13))
                    awaitSnapshot()
                    assertNull(vm.state.value.confirmation)
                    assertTrue(ports.removed.isEmpty())
                }
            } finally { vm.close(); owner.cancel() }
        }
    }
    test("component source menu delivers admission and sign-in emits only unbound intent") {
        val ports = RendererAuthPorts(KodexAuthSource.Kodex)
        val owner = Job()
        val vm = createAuthenticationSettingsViewModel(ports, CoroutineScope(Dispatchers.Unconfined + owner))
        try {
            runMosaicTest {
                setContentAndSnapshot {
                    Box {
                        TuiPopupHost(Modifier.width(120).height(30)) {
                            AuthenticationSettingsComponent(vm)
                        }
                    }
                }
                sendKeyEvent(KeyboardEvent(codepoint = 13)) // Open source menu.
                val menu = awaitSnapshot()
                assertTrue("Codex" in menu && "Kodex" in menu, menu)
                sendKeyEvent(KeyboardEvent(codepoint = 13)) // Selected source admission.
                awaitSnapshot()
                assertEquals(1, ports.writes.size)
                val content = flushAuthenticationInput()
                val lines = content.lines()
                val row = lines.indexOfFirst { "[Sign in again]" in it }
                assertTrue(row >= 0, content)
                val column = lines[row].indexOf("[Sign in again]") + 1
                sendMouseEvent(MouseEvent(column, row, MouseEvent.Type.Press, MouseEvent.Button.Left))
                flushAuthenticationInput()
                sendMouseEvent(MouseEvent(column, row, MouseEvent.Type.Release))
                flushAuthenticationInput()
                assertEquals(1, ports.logins)
                assertTrue(ports.removed.isEmpty())
            }
        } finally { vm.close(); owner.cancel() }
    }
    test("closed component renders nothing and shared banner can be delegated to host") {
        for (closed in listOf(false, true)) {
            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    Column(Modifier.width(120)) {
                        AuthenticationSettingsContent(
                            AuthenticationSettingsState(KodexAuthSource.Kodex,
                                SettingsAuthenticationState.Authenticated(), operationFailure = true, closed = closed),
                            rememberTuiDropdownState(), {}, {}, {}, showOperationFailure = false,
                        )
                    }
                }
                assertFalse("A settings operation failed." in snapshot, snapshot)
                assertEquals(!closed, "OpenAI account" in snapshot)
            }
        }
    }
}

private suspend fun TestMosaic<String>.flushAuthenticationInput(): String =
    try { awaitSnapshot() } catch (_: TimeoutCancellationException) {
        draw().render(AnsiLevel.NONE, supportsKittyUnderlines = false)
    }

private class RendererAuthPorts(source: KodexAuthSource) : AuthenticationSettingsDependencies {
    override val selectedSource = MutableStateFlow(source)
    override val authentication = MutableStateFlow<SettingsAuthenticationState>(SettingsAuthenticationState.Authenticated())
    override val operationFailure = MutableStateFlow(false)
    val removed = mutableListOf<KodexAuthSource>()
    val writes = mutableListOf<KodexAuthSource>()
    var logins = 0
    override fun updateSource(source: KodexAuthSource): Boolean { writes += source; return true }
    override suspend fun remove(source: KodexAuthSource) { removed += source }
    override fun openLogin() { logins++ }
    override fun reportFailure(failure: Throwable) { operationFailure.value = true }
    override fun dismissFailure() { operationFailure.value = false }
}
