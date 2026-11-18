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
import io.github.stream29.kodex.rpc.local.ActiveRpcPeer
import io.github.stream29.kodex.rpc.local.ManagedFrontendRpcConnection
import io.github.stream29.kodex.rpc.models.OAuthAuthorization
import io.github.stream29.kodex.rpc.models.OAuthLoginEvent
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.io.files.Path
import kotlinx.rpc.RpcCall
import kotlinx.rpc.RpcClient
import kotlin.test.*

val rpcOAuthTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("frontend uses one collection for authorization and even an immediately delivered result") {
        frontend {
            var collections = 0
            val rpc = object : GlobalRpc by services.global {
                override fun startOAuthLogin(target: OAuthTarget) = flow {
                    assertEquals(Target, target)
                    collections++
                    emit(OAuthLoginEvent.Authorization(Prepared))
                    emit(OAuthLoginEvent.Committed)
                }
                override suspend fun cancelOAuthLogin(attemptId: Long): Unit = error("not an explicit cancel")
            }
            val attempt = startRpcOAuth(rpc, Target, this)
            assertEquals(Prepared.url, attempt.authorizationUrl)
            attempt.awaitCompletion()
            assertEquals(1, collections)
        }
    }

    test("cancelling an observer releases pending work without exact cancellation") {
        frontend {
            val released = CompletableDeferred<Unit>()
            val rpc = object : GlobalRpc by services.global {
                override fun startOAuthLogin(target: OAuthTarget) = flow<OAuthLoginEvent> {
                    emit(OAuthLoginEvent.Authorization(Prepared))
                    awaitCancellation()
                }.onCompletion { released.complete(Unit) }
                override suspend fun cancelOAuthLogin(attemptId: Long): Unit = error("not an explicit cancel")
            }
            val attempt = startRpcOAuth(rpc, Target, this)
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { attempt.awaitCompletion() }
            waiter.cancelAndJoin()
            released.await()
        }
    }

    test("explicit cancel uses the exact returned identity and also releases local observation") {
        frontend {
            val cancelled = CompletableDeferred<Long>()
            val allowedToFinish = CompletableDeferred<Unit>()
            val released = CompletableDeferred<Unit>()
            val rpc = object : GlobalRpc by services.global {
                override fun startOAuthLogin(target: OAuthTarget) = flow<OAuthLoginEvent> {
                    emit(OAuthLoginEvent.Authorization(Prepared))
                    awaitCancellation()
                }.onCompletion { released.complete(Unit) }
                override suspend fun cancelOAuthLogin(attemptId: Long) {
                    cancelled.complete(attemptId)
                    allowedToFinish.await()
                }
            }
            val attempt = startRpcOAuth(rpc, Target, this)
            attempt.cancel()
            assertEquals(Prepared.attemptId, cancelled.await())
            assertFalse(released.isCompleted)
            allowedToFinish.complete(Unit)
            assertFailsWith<CancellationException> { attempt.awaitCompletion() }
            released.await()
        }
    }

    test("an already cancelled owner never starts a login") {
        frontend {
            var starts = 0
            val deadOwner = SupervisorJob().also { it.cancel() }
            val rpc = object : GlobalRpc by services.global {
                override fun startOAuthLogin(target: OAuthTarget) = flow<OAuthLoginEvent> {
                    starts++
                    emit(OAuthLoginEvent.Authorization(Prepared))
                }
            }
            assertFailsWith<CancellationException> { startRpcOAuth(rpc, Target, CoroutineScope(coroutineContext + deadOwner)) }
            assertEquals(0, starts)
        }
    }

    test("lost start reply never guesses an attempt identity or retries") {
        frontend {
            var starts = 0
            val rpc = object : GlobalRpc by services.global {
                override fun startOAuthLogin(target: OAuthTarget) = flow<OAuthLoginEvent> {
                    starts++
                    error("private provider value")
                }
                override suspend fun cancelOAuthLogin(attemptId: Long): Unit = error("no identity delivered")
            }
            val failure = assertFailsWith<IllegalStateException> { startRpcOAuth(rpc, Target, this) }
            assertEquals("Unable to prepare sign-in.", failure.message)
            assertEquals(1, starts)
        }
    }

    test("lost operation after authorization is sanitized and not replayed") {
        frontend {
            var starts = 0
            val rpc = object : GlobalRpc by services.global {
                override fun startOAuthLogin(target: OAuthTarget) = flow<OAuthLoginEvent> {
                    starts++
                    emit(OAuthLoginEvent.Authorization(Prepared))
                    error("private provider value")
                }
                override suspend fun cancelOAuthLogin(attemptId: Long): Unit = error("unknown is not cancellation")
            }
            val attempt = startRpcOAuth(rpc, Target, this)
            val failure = assertFailsWith<IllegalStateException> { attempt.awaitCompletion() }
            assertEquals("Sign-in failed. Check the current authentication state before trying again.", failure.message)
            assertEquals(1, starts)
        }
    }

    test("transport replacement cancels the old operation and cannot replay it or cancel on a new peer") {
        coroutineScope {
            val stopped = CompletableDeferred<Unit>()
            val connection = ManagedFrontendRpcConnection()
            var firstCollections = 0
            var replacementCalls = 0
            val original = ActiveRpcPeer(object : RpcClient {
                override suspend fun <T> call(call: RpcCall): T = error("No unary call on the detached peer.")
                @Suppress("UNCHECKED_CAST")
                override fun <T> callServerStreaming(call: RpcCall): Flow<T> = flow {
                    firstCollections++
                    try {
                        emit(OAuthLoginEvent.Authorization(Prepared) as T)
                        awaitCancellation()
                    } finally {
                        stopped.complete(Unit)
                    }
                }
            }) { true }
            val replacement = ActiveRpcPeer(object : RpcClient {
                override suspend fun <T> call(call: RpcCall): T {
                    replacementCalls++
                    error("Exact cancellation must not target a replacement peer.")
                }
                override fun <T> callServerStreaming(call: RpcCall): Flow<T> = flow {
                    replacementCalls++
                    error("An OAuth operation must not be recollected on a replacement peer.")
                }
            }) { true }
            connection.install(original)
            val rpc = RpcServices(connection.bindOperation()).global
            val attempt = startRpcOAuth(rpc, Target, this)
            connection.detach(original)
            connection.install(replacement)
            assertFailsWith<IllegalStateException> { attempt.awaitCompletion() }
            stopped.await()
            assertFailsWith<IllegalStateException> { rpc.cancelOAuthLogin(Prepared.attemptId) }
            assertEquals(1, firstCollections)
            assertEquals(0, replacementCalls)
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
            override fun startOAuthLogin(target: OAuthTarget) =
                services.global.startOAuthLogin(target).onCompletion { released.complete(Unit) }
        }
        val vm = createOpenAiLoginViewModel(
            dependencies = io.github.stream29.kodex.app.settings.contract.OpenAiLoginDependencies {
                startRpcOAuth(rpc, OAuthTarget.OpenAi(source), this)
            },
            ownerScope = this,
        )
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
