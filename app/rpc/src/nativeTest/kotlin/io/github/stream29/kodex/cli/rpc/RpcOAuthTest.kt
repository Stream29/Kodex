package io.github.stream29.kodex.cli.rpc

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.settings.createOpenAiLoginViewModel
import io.github.stream29.kodex.app.settings.contract.OpenAiLoginEffect
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationState
import io.github.stream29.kodex.cli.settings.KodexAuthSource
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import io.github.stream29.kodex.rpc.models.OAuthAuthorization
import io.github.stream29.kodex.rpc.models.OAuthTarget
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.io.files.Path
import kotlin.test.*

val rpcOAuthTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("frontend observes the exact backend attempt and releases only its pending ownership") {
        frontend {
            val completed = CompletableDeferred<Unit>()
            val released = CompletableDeferred<Long>()
            val rpc = object : GlobalRpc by services.global {
                override suspend fun startOAuthLogin(target: OAuthTarget): OAuthAuthorization {
                    assertEquals(Target, target)
                    return Prepared
                }
                override suspend fun awaitOAuthLogin(attemptId: Long) {
                    assertEquals(Prepared.attemptId, attemptId)
                    completed.await()
                }
                override suspend fun abandonPendingOAuthLogin(attemptId: Long) { released.complete(attemptId) }
                override suspend fun cancelOAuthLogin(attemptId: Long): Unit = error("not an explicit cancel")
            }
            val attempt = startRpcOAuth(rpc, Target, this)
            assertEquals(Prepared.url, attempt.authorizationUrl)
            completed.complete(Unit)
            attempt.awaitCompletion()
            assertEquals(Prepared.attemptId, released.await())
        }
    }

    test("cancelling an observer releases pending work without exact cancellation") {
        frontend {
            val released = CompletableDeferred<Long>()
            val rpc = object : GlobalRpc by services.global {
                override suspend fun startOAuthLogin(target: OAuthTarget) = Prepared
                override suspend fun awaitOAuthLogin(attemptId: Long): Unit = awaitCancellation()
                override suspend fun abandonPendingOAuthLogin(attemptId: Long) { released.complete(attemptId) }
                override suspend fun cancelOAuthLogin(attemptId: Long): Unit = error("not an explicit cancel")
            }
            val attempt = startRpcOAuth(rpc, Target, this)
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { attempt.awaitCompletion() }
            waiter.cancelAndJoin()
            assertEquals(Prepared.attemptId, released.await())
        }
    }

    test("explicit cancel uses the exact returned identity and also releases local observation") {
        frontend {
            val cancelled = CompletableDeferred<Long>()
            val released = CompletableDeferred<Long>()
            val rpc = object : GlobalRpc by services.global {
                override suspend fun startOAuthLogin(target: OAuthTarget) = Prepared
                override suspend fun awaitOAuthLogin(attemptId: Long): Unit = awaitCancellation()
                override suspend fun abandonPendingOAuthLogin(attemptId: Long) { released.complete(attemptId) }
                override suspend fun cancelOAuthLogin(attemptId: Long) { cancelled.complete(attemptId) }
            }
            val attempt = startRpcOAuth(rpc, Target, this)
            attempt.cancel()
            assertFailsWith<CancellationException> { attempt.awaitCompletion() }
            assertEquals(Prepared.attemptId, cancelled.await())
            assertEquals(Prepared.attemptId, released.await())
        }
    }

    test("an already cancelled owner still releases a delivered pending attempt") {
        frontend {
            val released = CompletableDeferred<Long>()
            val deadOwner = SupervisorJob().also { it.cancel() }
            val rpc = object : GlobalRpc by services.global {
                override suspend fun startOAuthLogin(target: OAuthTarget) = Prepared
                override suspend fun awaitOAuthLogin(attemptId: Long): Unit = error("must not start")
                override suspend fun abandonPendingOAuthLogin(attemptId: Long) { released.complete(attemptId) }
            }
            val attempt = startRpcOAuth(rpc, Target, CoroutineScope(coroutineContext + deadOwner))
            assertFailsWith<CancellationException> { attempt.awaitCompletion() }
            assertEquals(Prepared.attemptId, released.await())
        }
    }

    test("lost start reply never guesses an attempt identity or retries") {
        frontend {
            var starts = 0
            val rpc = object : GlobalRpc by services.global {
                override suspend fun startOAuthLogin(target: OAuthTarget): OAuthAuthorization {
                    starts++
                    error("private provider value")
                }
                override suspend fun abandonPendingOAuthLogin(attemptId: Long): Unit = error("no identity delivered")
                override suspend fun cancelOAuthLogin(attemptId: Long): Unit = error("no identity delivered")
            }
            val failure = assertFailsWith<IllegalStateException> { startRpcOAuth(rpc, Target, this) }
            assertEquals("Unable to prepare sign-in.", failure.message)
            assertEquals(1, starts)
        }
    }

    test("lost observation reply is sanitized and neither start nor await is replayed") {
        frontend {
            var starts = 0
            var observes = 0
            val released = CompletableDeferred<Long>()
            val rpc = object : GlobalRpc by services.global {
                override suspend fun startOAuthLogin(target: OAuthTarget): OAuthAuthorization {
                    starts++
                    return Prepared
                }
                override suspend fun awaitOAuthLogin(attemptId: Long) {
                    observes++
                    error("private provider value")
                }
                override suspend fun abandonPendingOAuthLogin(attemptId: Long) { released.complete(attemptId) }
                override suspend fun cancelOAuthLogin(attemptId: Long): Unit = error("unknown is not cancellation")
            }
            val attempt = startRpcOAuth(rpc, Target, this)
            val failure = assertFailsWith<IllegalStateException> { attempt.awaitCompletion() }
            assertEquals("Sign-in failed. Check the current authentication state before trying again.", failure.message)
            assertEquals(1, starts)
            assertEquals(1, observes)
            assertEquals(Prepared.attemptId, released.await())
        }
    }

    for (source in KodexAuthSource.entries) {
        test("closing frontend after actual callback preserves backend commit for $source") {
            acceptedFrontendLifetime(source, explicitCancel = false)
        }
    }
    test("explicit frontend cancel still cancels an accepted exchange") {
        acceptedFrontendLifetime(KodexAuthSource.Kodex, explicitCancel = true)
    }
}

private suspend fun acceptedFrontendLifetime(source: KodexAuthSource, explicitCancel: Boolean) {
    val login = GatedBackendLogin()
    frontend(login = login) {
        val released = CompletableDeferred<Unit>()
        val rpc = object : GlobalRpc by services.global {
            override suspend fun abandonPendingOAuthLogin(attemptId: Long) {
                services.global.abandonPendingOAuthLogin(attemptId)
                released.complete(Unit)
            }
        }
        val vm = createOpenAiLoginViewModel(this) { startRpcOAuth(rpc, OAuthTarget.OpenAi(source), this) }
        val path = if (source == KodexAuthSource.Codex) Path(home, "codex", "auth.json") else Path(home, "auth.yml")
        try {
            vm.start()
            val effect = assertIs<OpenAiLoginEffect.OpenExternalUrl>(vm.effects.first())
            val url = Url(effect.url)
            val callback = URLBuilder(requireNotNull(url.parameters["redirect_uri"])).apply {
                parameters.append("state", requireNotNull(url.parameters["state"]))
                parameters.append("code", "fixture-code")
            }.buildString()
            HttpClient(CIO).use { client -> assertEquals(HttpStatusCode.OK, client.get(callback).status) }
            login.entered.await()
            assertNull(SystemCoroutineFileSystem.metadataOrNull(path))
            if (explicitCancel) vm.cancel() else vm.close()
            released.await()
            if (explicitCancel) {
                login.cancelled.await()
                assertNull(SystemCoroutineFileSystem.metadataOrNull(path))
            } else {
                assertFalse(login.cancelled.isCompleted)
                login.proceed.complete(Unit)
                val selected = services.global.getSettings()
                if (selected.authSource != source) {
                    assertTrue(services.global.compareAndSetSettings(selected, selected.copy(authSource = source)))
                }
                services.global.getAuthenticationFlow().first { it is SettingsAuthenticationState.Authenticated }
                assertNotNull(SystemCoroutineFileSystem.metadataOrNull(path))
            }
            assertEquals(1, login.exchanges)
        } finally {
            login.proceed.complete(Unit)
            vm.close()
        }
    }
}

private val Prepared = OAuthAuthorization(9, "https://login.example.invalid/authorize?state=fixture")
private val Target = OAuthTarget.OpenAi(KodexAuthSource.Kodex)

private class GatedBackendLogin : OpenAiLoginClient {
    val entered = CompletableDeferred<Unit>()
    val proceed = CompletableDeferred<Unit>()
    val cancelled = CompletableDeferred<Unit>()
    var exchanges = 0
    override fun authorizationUrl(request: OpenAiLoginAuthorization): String =
        URLBuilder("https://login.example.invalid/authorize").apply {
            parameters.append("state", request.state)
            parameters.append("redirect_uri", request.redirectUri)
        }.buildString()
    override suspend fun exchangeAuthorizationCode(
        request: OpenAiAuthorizationCodeExchange,
    ): OpenAiLoginResult<OpenAiSubscriptionTokens> {
        exchanges++
        entered.complete(Unit)
        try { proceed.await() }
        catch (failure: CancellationException) { cancelled.complete(Unit); throw failure }
        return OpenAiResult.Success(OpenAiSubscriptionTokens("fake-id", "fake-access", "fake-refresh", "fake-account"))
    }
    override suspend fun refreshSubscriptionTokens(
        refreshToken: String,
    ): OpenAiLoginResult<OpenAiSubscriptionTokenRefresh> = error("Fresh fixture credentials must not refresh.")
}
