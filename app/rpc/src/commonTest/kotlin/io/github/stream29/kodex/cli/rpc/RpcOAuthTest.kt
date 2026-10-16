@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.cli.rpc

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.settings.createOpenAiLoginViewModel
import io.github.stream29.kodex.app.settings.contract.OpenAiLoginEffect
import io.github.stream29.kodex.app.settings.contract.OpenAiLoginState
import io.github.stream29.kodex.cli.settings.KodexAuthSource
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import io.github.stream29.kodex.rpc.models.OAuthAuthorization
import io.github.stream29.kodex.rpc.models.OAuthTarget
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationState
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path
import kotlin.test.*

val rpcOAuthTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("frontend callback crosses real RPC and commits only the captured credential destination") {
        frontend {
            val listener = FakeListener()
            val settings = services.global.getSettings()
            val destination = if (settings.authSource == KodexAuthSource.Kodex) KodexAuthSource.Codex else KodexAuthSource.Kodex
            val attempt = startRpcOAuth(services.global, OAuthTarget.OpenAi(destination), this, null, listener::start)
            listener.callback.complete("${listener.redirectUri}?state=${listener.state}&code=test")
            attempt.awaitCompletion()
            assertEquals(settings.authSource, services.global.getSettings().authSource)
            val targetPath = if (destination == KodexAuthSource.Codex) Path(home, "codex", "auth.json") else Path(home, "auth.yml")
            assertNotNull(SystemCoroutineFileSystem.metadataOrNull(targetPath))
            val selected = services.global.getSettings()
            assertTrue(services.global.compareAndSetSettings(selected, selected.copy(authSource = destination)))
            services.global.getAuthenticationFlow().first { it is SettingsAuthenticationState.Authenticated }
            services.global.removeAuthentication(destination)
            services.global.getAuthenticationFlow().first { it is SettingsAuthenticationState.Unavailable }
            assertEquals(1, listener.closed)
        }
    }
    test("actual loopback listener rejects wrong or repeated state and does not echo secrets") {
        coroutineScope {
            val listener = startLoopback(this, listOf("http://127.0.0.1/callback"), testPort = 0)
            val http = HttpClient(CIO)
            try {
                listener.expectState("expected")
                val wait = async { listener.awaitCallback() }
                val invalid = http.get("${listener.redirectUri}?code=private-code&state=wrong")
                assertEquals(HttpStatusCode.BadRequest, invalid.status)
                assertFalse(invalid.bodyAsText().contains("private-code"))
                assertFalse(wait.isCompleted)
                val duplicate = http.get("${listener.redirectUri}?code=private-code&state=expected&state=expected")
                assertEquals(HttpStatusCode.BadRequest, duplicate.status)
                assertFalse(wait.isCompleted)
                val valid = http.get("${listener.redirectUri}?code=private-code&state=expected")
                assertEquals(HttpStatusCode.OK, valid.status)
                assertFalse(valid.bodyAsText().contains("private-code"))
                assertEquals("${listener.redirectUri}?code=private-code&state=expected", wait.await())
            } finally {
                http.close()
                withContext(NonCancellable) { listener.close() }
            }
        }
    }
    test("an occupied listener address fails locally without cancelling its frontend owner") {
        coroutineScope {
            val listener = startLoopback(this, listOf("http://127.0.0.1/callback"), testPort = 0)
            try {
                assertFailsWith<IllegalStateException> { startLoopback(this, listOf(listener.redirectUri)) }
                ensureActive()
                listener.expectState("still-live")
            } finally { withContext(NonCancellable) { listener.close() } }
        }
    }
    test("listener precedes preparation and only an exact callback is forwarded") {
        frontend {
            val listener = FakeListener()
            val submitted = mutableListOf<Pair<Long, String>>()
            val cancelled = mutableListOf<Long>()
            val target = OAuthTarget.OpenAi(KodexAuthSource.Codex)
            val rpc = object : GlobalRpc by services.global {
                override suspend fun startOAuthLogin(target: OAuthTarget, redirectUri: String): OAuthAuthorization {
                    assertTrue(listener.started)
                    assertEquals(OAuthTarget.OpenAi(KodexAuthSource.Codex), target)
                    assertEquals(listener.redirectUri, redirectUri)
                    return prepared
                }
                override suspend fun completeOAuthLogin(attemptId: Long, callbackUrl: String) {
                    submitted += attemptId to callbackUrl
                }
                override suspend fun cancelOAuthLogin(attemptId: Long) { cancelled += attemptId }
            }
            val attempt = startRpcOAuth(rpc, target, this, null, listener::start)
            assertEquals(prepared.url, attempt.authorizationUrl)
            assertEquals("opaque", listener.state)
            listener.callback.complete("${listener.redirectUri}?code=secret&state=opaque")
            attempt.awaitCompletion()
            assertEquals(listOf(9L to "${listener.redirectUri}?code=secret&state=opaque"), submitted)
            assertEquals(1, listener.closed)
            assertTrue(cancelled.isEmpty())
        }
    }
    test("cancel closes the frontend listener and cancels only its exact backend attempt") {
        frontend {
            val listener = FakeListener()
            val ids = mutableListOf<Long>()
            val rpc = object : GlobalRpc by services.global {
                override suspend fun startOAuthLogin(target: OAuthTarget, redirectUri: String) = prepared
                override suspend fun cancelOAuthLogin(attemptId: Long) { ids += attemptId }
                override suspend fun completeOAuthLogin(attemptId: Long, callbackUrl: String): Unit = error("unexpected")
            }
            val attempt = startRpcOAuth(rpc, target, this, null, listener::start)
            attempt.cancel()
            assertFailsWith<CancellationException> { attempt.awaitCompletion() }
            assertEquals(1, listener.closed)
            assertEquals(listOf(9L), ids)
        }
    }
    test("lost prepare reply releases listener without guessing an attempt id or retrying") {
        frontend {
            val listener = FakeListener()
            var calls = 0
            val rpc = object : GlobalRpc by services.global {
                override suspend fun startOAuthLogin(target: OAuthTarget, redirectUri: String): OAuthAuthorization {
                    calls++; error("secret URL in provider exception")
                }
                override suspend fun cancelOAuthLogin(attemptId: Long): Unit = error("no delivered id")
            }
            val failure = assertFailsWith<IllegalStateException> {
                startRpcOAuth(rpc, target, this, null, listener::start)
            }
            assertEquals("Unable to prepare sign-in.", failure.message)
            assertEquals(1, calls)
            assertEquals(1, listener.closed)
        }
    }
    test("preparation cancellation releases a listener whose handle was never returned") {
        frontend {
            val listener = FakeListener()
            val entered = CompletableDeferred<Unit>()
            val rpc = object : GlobalRpc by services.global {
                override suspend fun startOAuthLogin(target: OAuthTarget, redirectUri: String): OAuthAuthorization {
                    entered.complete(Unit); awaitCancellation()
                }
            }
            val work = launch { startRpcOAuth(rpc, target, this, null, listener::start) }
            entered.await()
            work.cancelAndJoin()
            assertEquals(1, listener.closed)
        }
    }
    test("ten minute timeout applies only before callback and cleanup cancellation is bounded") {
        frontend {
            val delegate = services.global
            runTest {
                val listener = FakeListener()
                val cancelled = mutableListOf<Long>()
                val rpc = object : GlobalRpc by delegate {
                    override suspend fun startOAuthLogin(target: OAuthTarget, redirectUri: String) = prepared
                    override suspend fun cancelOAuthLogin(attemptId: Long) {
                        cancelled += attemptId
                        awaitCancellation()
                    }
                }
                val attempt = startRpcOAuth(rpc, target, this, null, listener::start)
                advanceTimeBy(600_000)
                runCurrent()
                assertEquals(1, listener.closed)
                assertEquals(listOf(9L), cancelled)
                advanceTimeBy(5_001)
                assertFailsWith<IllegalStateException> { attempt.awaitCompletion() }
            }
        }
    }
    test("valid callback ends pending deadline without timing out an accepted exchange") {
        frontend {
            val delegate = services.global
            runTest {
                val listener = FakeListener()
                val exchanging = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val rpc = object : GlobalRpc by delegate {
                    override suspend fun startOAuthLogin(target: OAuthTarget, redirectUri: String) = prepared
                    override suspend fun completeOAuthLogin(attemptId: Long, callbackUrl: String) {
                        exchanging.complete(Unit); release.await()
                    }
                    override suspend fun cancelOAuthLogin(attemptId: Long): Unit = error("successful completion")
                }
                val attempt = startRpcOAuth(rpc, target, this, null, listener::start)
                listener.callback.complete("callback")
                runCurrent()
                assertTrue(exchanging.isCompleted)
                advanceTimeBy(1_200_000)
                assertEquals(0, listener.closed)
                release.complete(Unit)
                attempt.awaitCompletion()
                assertEquals(1, listener.closed)
            }
        }
    }
    test("browser-open failure remains a retryable local effect and closing disposes the attempt") {
        frontend {
            val listener = FakeListener()
            val cancelled = CompletableDeferred<Long>()
            val rpc = object : GlobalRpc by services.global {
                override suspend fun startOAuthLogin(target: OAuthTarget, redirectUri: String) = prepared
                override suspend fun cancelOAuthLogin(attemptId: Long) { cancelled.complete(attemptId) }
            }
            val vm = createOpenAiLoginViewModel(this) { startRpcOAuth(rpc, target, this, null, listener::start) }
            try {
                vm.start()
                val effect = assertIs<OpenAiLoginEffect.OpenExternalUrl>(vm.effects.first())
                vm.onBrowserOpenFailed(effect.attemptId)
                assertIs<OpenAiLoginState.BrowserOpenFailed>(vm.state.value)
                vm.retryBrowser(effect.attemptId)
                assertEquals(effect, vm.effects.first())
                assertEquals(0, listener.closed)
            } finally { vm.close() }
            assertEquals(9L, cancelled.await())
            assertEquals(1, listener.closed)
        }
    }
    test("MCP uses the configured redirect rather than an OpenAI default") {
        frontend {
            val listener = FakeListener("http://localhost:8765/custom")
            val rpc = object : GlobalRpc by services.global {
                override suspend fun startOAuthLogin(target: OAuthTarget, redirectUri: String): OAuthAuthorization {
                    assertEquals(OAuthTarget.Mcp("server"), target)
                    assertEquals(listener.redirectUri, redirectUri)
                    return prepared
                }
                override suspend fun cancelOAuthLogin(attemptId: Long) {}
            }
            val attempt = startRpcOAuth(rpc, OAuthTarget.Mcp("server"), this, listener.redirectUri, listener::start)
            attempt.cancel()
            assertFailsWith<CancellationException> { attempt.awaitCompletion() }
        }
    }
}

private val prepared = OAuthAuthorization(9, "https://login.example.invalid/authorize?state=opaque")
private val target = OAuthTarget.OpenAi(KodexAuthSource.Kodex)
private class FakeListener(override val redirectUri: String = "http://localhost:1455/auth/callback") : OAuthCallbackListener {
    val callback = CompletableDeferred<String>()
    var started = false
    var state: String? = null
    var closed = 0
    suspend fun start(scope: CoroutineScope, candidates: List<String>): OAuthCallbackListener {
        scope.coroutineContext.ensureActive()
        assertTrue(redirectUri in candidates)
        started = true
        return this
    }
    override fun expectState(value: String) { state = value }
    override suspend fun awaitCallback(): String = callback.await()
    override suspend fun close() { closed++; callback.cancel() }
}
