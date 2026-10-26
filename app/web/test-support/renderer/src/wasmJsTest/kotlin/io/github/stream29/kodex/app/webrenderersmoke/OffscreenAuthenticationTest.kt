package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.collectAsState
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.authenticationsettings.*
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationOperation
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationOperationState
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationState
import io.github.stream29.kodex.app.ui.UiText
import io.github.stream29.kodex.app.ui.compose.rememberComposeSettingsRenderer
import io.github.stream29.kodex.cli.authenticationsettings.*
import io.github.stream29.kodex.cli.settings.KodexAuthSource
import io.github.stream29.kodex.openai.OpenAiAuthState
import io.github.stream29.kodex.openai.OpenAiSubscriptionPlan
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

val offscreenAuthenticationTest by testSuite {
    val reasons = listOf(
        OpenAiAuthState.Unavailable.NotLoaded to "Authentication credentials have not been loaded yet.",
        OpenAiAuthState.Unavailable.CredentialsNotFound to "No credentials were found in the selected authentication source.",
        OpenAiAuthState.Unavailable.UnsupportedAuthMode to "The selected credentials use an unsupported authentication mode.",
        OpenAiAuthState.Unavailable.InvalidCredentials to "The selected credentials are malformed or incomplete.",
        OpenAiAuthState.Unavailable.CredentialSourceUnavailable to "The selected credential source could not be read.",
        OpenAiAuthState.Unavailable.UnexpectedFailure to "Authentication failed because of an unexpected internal error.",
    )
    for ((reason, expected) in reasons) {
        test("shared unavailable authentication $reason") { offscreenTest {
            var logins = 0
            val scene = OffscreenScene.create {
                AuthenticationSettingsBody(
                    AuthenticationSettingsState(KodexAuthSource.Codex, SettingsAuthenticationState.Unavailable(reason)),
                    { logins++ }, {}, {},
                ) { UiText("source") }
            }
            try {
                scene.settle { scene.hasText("Authentication unavailable: $expected") }
                assertTrue(scene.hasText("Codex credentials"))
                assertFalse(scene.hasText("Log out"))
                scene.click("Sign in")
                assertEquals(1, logins)
                scene.frame()
            } finally { scene.close() }
        } }
    }
    for ((auth, expected) in listOf(
        SettingsAuthenticationState.Authenticated(email = "safe@example.test", accountId = "hidden", planType = OpenAiSubscriptionPlan.Pro)
            to "Signed in as safe@example.test Plan: pro",
        SettingsAuthenticationState.Authenticated(accountId = "safe-account") to "Signed in as account safe-account",
        SettingsAuthenticationState.Authenticated() to "Signed in",
    )) {
        test("shared safe identity $expected") { offscreenTest {
            var logins = 0
            var logouts = 0
            val scene = OffscreenScene.create {
                AuthenticationSettingsBody(
                    AuthenticationSettingsState(KodexAuthSource.Kodex, auth),
                    { logins++ }, { logouts++ }, {},
                ) { UiText("source") }
            }
            try {
                scene.settle { scene.hasText(expected) }
                assertFalse(scene.hasText("hidden"))
                scene.click("Sign in again")
                scene.click("Log out")
                assertEquals(1, logins)
                assertEquals(1, logouts)
            } finally { scene.close() }
        } }
    }
    test("SigningOut controls are disabled and local failure acknowledges once without duplicate banner") { offscreenTest {
        val state = MutableStateFlow(AuthenticationSettingsState(
            KodexAuthSource.Kodex, SettingsAuthenticationState.Authenticated(),
            operation = SettingsAuthenticationOperationState.SigningOut,
        ))
        var calls = 0
        var dismissals = 0
        val scene = OffscreenScene.create {
            val value = state.collectAsState().value
            AuthenticationSettingsBody(value, { calls++ }, { calls++ }, { dismissals++ }) { enabled ->
                assertEquals(value.operation != SettingsAuthenticationOperationState.SigningOut, enabled)
            }
        }
        try {
            scene.settle { scene.hasText("Signing out…") }
            scene.click("Sign in again")
            scene.click("Log out")
            assertEquals(0, calls)
            state.value = state.value.copy(
                operation = SettingsAuthenticationOperationState.Failed(SettingsAuthenticationOperation.Logout),
                operationFailure = true,
            )
            scene.settle { scene.hasText("Could not log out. Check the selected source before trying again.") }
            assertFalse(scene.hasText("A settings operation failed."))
            scene.click("Dismiss")
            assertEquals(1, dismissals)
        } finally { scene.close() }
    } }
    test("real authentication page emits source and login intents without optimistic summary or ownership") { authScene { ports, child ->
        val scene = OffscreenScene.create { AuthenticationSettingsPage(child, rememberComposeSettingsRenderer()) }
        try {
            scene.settle { scene.hasDescription("Authentication: Kodex") }
            scene.clickDescription("Authentication: Kodex")
            scene.settle { scene.hasDescription("Authentication: Codex") }
            scene.clickDescription("Authentication: Codex")
            scene.settle { ports.sources.size == 1 }
            assertEquals(listOf(KodexAuthSource.Codex), ports.sources)
            assertEquals(KodexAuthSource.Kodex, ports.selectedSource.value)
            scene.click("Sign in again")
            assertEquals(1, ports.logins)
            ports.operationFailure.value = true
            scene.settle { scene.hasText("A settings operation failed.") }
            scene.click("Dismiss")
            scene.settle { !scene.hasText("A settings operation failed.") }
            assertEquals(1, ports.dismissals)
            assertTrue(ports.removed.isEmpty())
        } finally { scene.close() }
        assertFalse(child.state.value.closed)
    } }
    test("confirmation keeps exact identity with live source then admits one captured remove") { authScene { ports, child ->
        child.requestLogout()
        val expected = assertNotNull(child.state.value.confirmation)
        var staleDismiss: (() -> Unit)? = null
        val scene = OffscreenScene.create {
            AuthenticationLogoutHost(child) { title, dismiss, content ->
                staleDismiss = dismiss
                UiText(title)
                content()
            }
        }
        try {
            scene.settle { scene.hasText("Remove Kodex private credentials?") }
            val oldDismiss = assertNotNull(staleDismiss)
            ports.selectedSource.value = KodexAuthSource.Codex
            scene.settle { scene.hasText("Remove credentials from ~/.codex/auth.json?") }
            assertSame(expected, child.state.value.confirmation)
            scene.click("Log out")
            scene.settle { ports.removed.size == 1 && !scene.hasText("Log out of OpenAI") }
            assertEquals(listOf(KodexAuthSource.Codex), ports.removed)
            assertIs<SettingsAuthenticationState.Authenticated>(ports.authentication.value)
            child.confirmLogout(expected)
            assertEquals(1, ports.removed.size)
            child.requestLogout()
            val replacement = assertNotNull(child.state.value.confirmation)
            assertNotSame(expected, replacement)
            scene.settle { scene.hasText("Log out of OpenAI") }
            oldDismiss()
            assertSame(replacement, child.state.value.confirmation)
            scene.click("Cancel")
            scene.settle { !scene.hasText("Log out of OpenAI") }
            assertNull(child.state.value.confirmation)
            assertEquals(1, ports.removed.size)
        } finally { scene.close() }
    } }
}

private suspend fun authScene(block: suspend (AuthPorts, AuthenticationSettingsViewModel) -> Unit) = offscreenTest {
    val ports = AuthPorts()
    val owner = CoroutineScope(currentCoroutineContext() + Job(currentCoroutineContext()[Job]))
    val child = createAuthenticationSettingsViewModel(ports, owner)
    try { block(ports, child) } finally { child.close(); owner.coroutineContext.job.cancelAndJoin() }
}

internal class AuthPorts : AuthenticationSettingsDependencies {
    override val selectedSource = MutableStateFlow(KodexAuthSource.Kodex)
    override val authentication = MutableStateFlow<SettingsAuthenticationState>(SettingsAuthenticationState.Authenticated())
    override val operationFailure = MutableStateFlow(false)
    val sources = mutableListOf<KodexAuthSource>()
    val removed = mutableListOf<KodexAuthSource>()
    var logins = 0
    var dismissals = 0
    override fun updateSource(source: KodexAuthSource): Boolean { sources += source; return true }
    override suspend fun remove(source: KodexAuthSource) { removed += source }
    override fun openLogin() { logins++ }
    override fun reportFailure(failure: Throwable) { throw AssertionError("Unexpected command failure", failure) }
    override fun dismissFailure() { dismissals++; operationFailure.value = false }
}
