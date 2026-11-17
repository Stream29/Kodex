@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.app.authenticationsettings

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationOperation
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationOperationState
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationState
import io.github.stream29.kodex.cli.settings.KodexAuthSource
import io.github.stream29.kodex.openai.OpenAiAuthState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

val authenticationSettingsViewModelTest by testSuite {
    test("initial observation and independent source summary changes execute no commands") {
        runTest {
            val deps = AuthenticationPorts()
            val vm = createAuthenticationSettingsViewModel(deps, backgroundScope)
            assertEquals(deps.selectedSource.value, vm.state.value.selectedSource)
            assertEquals(deps.authentication.value, vm.state.value.authentication)
            runCurrent()
            for (reason in listOf(
                OpenAiAuthState.Unavailable.NotLoaded,
                OpenAiAuthState.Unavailable.CredentialsNotFound,
                OpenAiAuthState.Unavailable.UnsupportedAuthMode,
                OpenAiAuthState.Unavailable.InvalidCredentials,
                OpenAiAuthState.Unavailable.CredentialSourceUnavailable,
                OpenAiAuthState.Unavailable.UnexpectedFailure,
            )) {
                deps.authentication.value = SettingsAuthenticationState.Unavailable(reason)
                runCurrent()
                assertEquals(deps.authentication.value, vm.state.value.authentication)
            }
            deps.selectedSource.value = KodexAuthSource.Codex
            runCurrent()
            assertEquals(KodexAuthSource.Codex, vm.state.value.selectedSource)
            assertTrue(deps.removed.isEmpty())
            assertTrue(deps.writes.isEmpty())
            assertEquals(0, deps.logins)
            vm.close()
        }
    }
    test("source admission is not publication and rejected writes are not failures") {
        runTest {
            val deps = AuthenticationPorts()
            val vm = createAuthenticationSettingsViewModel(deps, backgroundScope)
            assertTrue(vm.updateSource(KodexAuthSource.Codex))
            assertEquals(KodexAuthSource.Kodex, vm.state.value.selectedSource)
            deps.admit = false
            assertFalse(vm.updateSource(KodexAuthSource.Kodex))
            assertEquals(listOf(KodexAuthSource.Codex, KodexAuthSource.Kodex), deps.writes)
            assertTrue(deps.failures.isEmpty())
            vm.close()
            assertFalse(vm.updateSource(KodexAuthSource.Codex))
            assertEquals(2, deps.writes.size) // Already admitted writes are not withdrawn.
        }
    }
    for (source in KodexAuthSource.entries) {
        test("confirmation captures $source at command start not open or coroutine execution") {
            runTest {
                val deps = AuthenticationPorts()
                val vm = createAuthenticationSettingsViewModel(deps, backgroundScope)
                vm.requestLogout()
                val expected = assertNotNull(vm.state.value.confirmation)
                deps.selectedSource.value = source // Don't let projection collector catch up.
                vm.confirmLogout(expected)
                assertNull(vm.state.value.confirmation)
                assertEquals(SettingsAuthenticationOperationState.SigningOut, vm.state.value.operation)
                deps.selectedSource.value = if (source == KodexAuthSource.Codex) KodexAuthSource.Kodex else KodexAuthSource.Codex
                vm.confirmLogout(expected)
                vm.requestLogout()
                runCurrent()
                assertEquals(listOf(source), deps.removed)
                assertEquals(SettingsAuthenticationOperationState.Idle, vm.state.value.operation)
                assertEquals(deps.authentication.value, vm.state.value.authentication)
                vm.close()
            }
        }
    }
    test("confirmation display follows live source while retaining identity and stale callbacks cannot act") {
        runTest {
            val deps = AuthenticationPorts()
            val vm = createAuthenticationSettingsViewModel(deps, backgroundScope)
            runCurrent()
            vm.requestLogout()
            val old = assertNotNull(vm.state.value.confirmation)
            deps.selectedSource.value = KodexAuthSource.Codex
            runCurrent()
            assertSame(old, vm.state.value.confirmation)
            assertEquals(KodexAuthSource.Codex, vm.state.value.selectedSource)
            vm.requestLogout()
            val next = assertNotNull(vm.state.value.confirmation)
            assertNotSame(old, next)
            vm.cancelLogout(old)
            vm.confirmLogout(old)
            assertSame(next, vm.state.value.confirmation)
            vm.cancelLogout(AuthenticationLogoutConfirmation())
            assertSame(next, vm.state.value.confirmation)
            vm.hidePage()
            vm.confirmLogout(next)
            runCurrent()
            assertTrue(deps.removed.isEmpty())
            vm.close()
        }
    }
    test("SigningOut suppresses duplicate remove but not programmatic source writes or unbound login") {
        runTest {
            val deps = AuthenticationPorts()
            val gate = CompletableDeferred<Unit>()
            deps.removeBody = { gate.await() }
            val vm = createAuthenticationSettingsViewModel(deps, backgroundScope)
            vm.requestLogout()
            vm.confirmLogout(assertNotNull(vm.state.value.confirmation))
            runCurrent()
            vm.requestLogout()
            assertNull(vm.state.value.confirmation)
            assertTrue(vm.updateSource(KodexAuthSource.Codex))
            vm.requestLogin()
            vm.hidePage()
            vm.dismissFailure()
            assertEquals(SettingsAuthenticationOperationState.SigningOut, vm.state.value.operation)
            assertEquals(1, deps.logins)
            assertEquals(listOf(KodexAuthSource.Codex), deps.writes)
            assertEquals(listOf(KodexAuthSource.Kodex), deps.removed)
            gate.complete(Unit)
            runCurrent()
            assertEquals(SettingsAuthenticationOperationState.Idle, vm.state.value.operation)
            vm.close()
        }
    }
    test("noncancel removal failure has local safe state plus shared reporting without rewriting summary") {
        runTest {
            val deps = AuthenticationPorts()
            val failure = IllegalStateException("private diagnostic")
            deps.removeBody = { throw failure }
            val vm = createAuthenticationSettingsViewModel(deps, backgroundScope)
            vm.requestLogout()
            vm.confirmLogout(assertNotNull(vm.state.value.confirmation))
            runCurrent()
            assertEquals(SettingsAuthenticationOperationState.Failed(SettingsAuthenticationOperation.Logout),
                vm.state.value.operation)
            assertEquals<List<Throwable>>(listOf(failure), deps.failures)
            assertTrue(vm.state.value.operationFailure)
            assertEquals(deps.authentication.value, vm.state.value.authentication)
            vm.dismissFailure()
            runCurrent()
            assertEquals(SettingsAuthenticationOperationState.Idle, vm.state.value.operation)
            assertFalse(vm.state.value.operationFailure)
            assertEquals(1, deps.dismissals)
            assertEquals(1, deps.removed.size)
            vm.close()
        }
    }
    test("remove cancellation is not shared failure and may leave shared summary unchanged") {
        runTest {
            val deps = AuthenticationPorts()
            deps.removeBody = { throw CancellationException("cancel") }
            val vm = createAuthenticationSettingsViewModel(deps, backgroundScope)
            vm.requestLogout()
            vm.confirmLogout(assertNotNull(vm.state.value.confirmation))
            runCurrent()
            assertEquals(SettingsAuthenticationOperationState.Idle, vm.state.value.operation)
            assertTrue(deps.failures.isEmpty())
            vm.close()
        }
    }
    test("synchronous port exceptions report once while cancellation propagates") {
        runTest {
            val deps = AuthenticationPorts()
            val vm = createAuthenticationSettingsViewModel(deps, backgroundScope)
            deps.writeError = IllegalStateException("write")
            deps.loginError = IllegalStateException("login")
            assertFalse(vm.updateSource(KodexAuthSource.Codex))
            vm.requestLogin()
            assertEquals(2, deps.failures.size)
            deps.writeError = CancellationException("cancel")
            deps.loginError = CancellationException("cancel")
            assertFailsWith<CancellationException> { vm.updateSource(KodexAuthSource.Codex) }
            assertFailsWith<CancellationException> { vm.requestLogin() }
            assertEquals(2, deps.failures.size)
            vm.close()
        }
    }
    test("close cancels child wait and observation without closing shared sources or parent") {
        runTest {
            val deps = AuthenticationPorts()
            val waiting = CompletableDeferred<Unit>()
            var cancelled = false
            deps.removeBody = {
                try { waiting.await() } finally { cancelled = true }
            }
            val parent = Job()
            val vm = createAuthenticationSettingsViewModel(deps, CoroutineScope(coroutineContext + parent))
            vm.requestLogout()
            val token = assertNotNull(vm.state.value.confirmation)
            vm.confirmLogout(token)
            runCurrent()
            vm.close()
            vm.close()
            runCurrent()
            assertTrue(cancelled)
            assertTrue(parent.isActive)
            val closed = vm.state.value
            assertTrue(closed.closed)
            deps.selectedSource.value = KodexAuthSource.Codex
            deps.authentication.value = SettingsAuthenticationState.Unavailable(OpenAiAuthState.Unavailable.NotLoaded)
            deps.operationFailure.value = true
            vm.requestLogout()
            vm.requestLogin()
            vm.confirmLogout(token)
            vm.cancelLogout(token)
            vm.hidePage()
            vm.dismissFailure()
            runCurrent()
            assertEquals(closed, vm.state.value)
            assertEquals(0, deps.logins)
            assertTrue(deps.failures.isEmpty())
            parent.cancel()
        }
    }
    test("owner cancellation and already cancelled owner close the projection") {
        runTest {
            val parent = Job()
            val vm = createAuthenticationSettingsViewModel(AuthenticationPorts(), CoroutineScope(coroutineContext + parent))
            parent.cancel()
            runCurrent()
            assertTrue(vm.state.value.closed)
            val next = createAuthenticationSettingsViewModel(AuthenticationPorts(), CoroutineScope(coroutineContext + parent))
            assertTrue(next.state.value.closed)
        }
    }
}

private class AuthenticationPorts : AuthenticationSettingsDependencies {
    override val selectedSource = MutableStateFlow(KodexAuthSource.Kodex)
    override val authentication = MutableStateFlow<SettingsAuthenticationState>(
        SettingsAuthenticationState.Authenticated(email = "safe@example.test"))
    override val operationFailure = MutableStateFlow(false)
    val writes = mutableListOf<KodexAuthSource>()
    val removed = mutableListOf<KodexAuthSource>()
    val failures = mutableListOf<Throwable>()
    var logins = 0
    var dismissals = 0
    var admit = true
    var writeError: Throwable? = null
    var loginError: Throwable? = null
    var removeBody: suspend (KodexAuthSource) -> Unit = {}
    override fun updateSource(source: KodexAuthSource): Boolean {
        writeError?.let { throw it }
        writes += source
        return admit
    }
    override suspend fun remove(source: KodexAuthSource) { removed += source; removeBody(source) }
    override fun openLogin() { loginError?.let { throw it }; logins++ }
    override fun reportFailure(failure: Throwable) { failures += failure; operationFailure.value = true }
    override fun dismissFailure() { dismissals++; operationFailure.value = false }
}
