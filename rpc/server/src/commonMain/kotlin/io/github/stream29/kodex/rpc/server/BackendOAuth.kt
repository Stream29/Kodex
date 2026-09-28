package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.cli.auth.BackendFileSystemAuthStore
import io.github.stream29.kodex.cli.auth.prepareOpenAiLogin
import io.github.stream29.kodex.cli.settings.KodexAuthSource
import io.github.stream29.kodex.mcp.contract.McpOAuthLoginAttempt
import io.github.stream29.kodex.mcp.contract.McpOAuthLoginAttemptFactory
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.mcp.impl.McpManagerImpl
import io.github.stream29.kodex.mcp.impl.PreparedMcpOAuthLogin
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient
import io.github.stream29.kodex.rpc.models.OAuthAuthorization
import io.github.stream29.kodex.rpc.models.OAuthTarget
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.ktor.http.Url
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration

/** Supplies listener-free attempts to the original manager's commit/cleanup path. */
public class BackendMcpOAuthBridge(
    private val prepare: suspend (McpServerConfiguration.StreamableHttp) -> PreparedMcpOAuthLogin,
) : McpOAuthLoginAttemptFactory {
    override suspend fun create(configuration: McpServerConfiguration.StreamableHttp): McpOAuthLoginAttempt =
        RemoteMcpAttempt(prepare(configuration))
}

private class RemoteMcpAttempt(val prepared: PreparedMcpOAuthLogin) : McpOAuthLoginAttempt {
    val code = CompletableDeferred<String>()
    override val authorizationUrl get() = prepared.authorizationUrl
    override val preparedConfiguration get() = prepared.preparedConfiguration
    override suspend fun awaitInitialized() = prepared.exchangeCode(code.await())
    override fun close() { code.cancel() }
}

/**
 * Backend-private attempt registry. Handles are unique across target kinds;
 * completion/cancellation never resolve a target from current frontend state.
 */
public class BackendOAuth internal constructor(
    scope: CoroutineScope,
    private val credentials: BackendFileSystemAuthStore,
    private val openAi: OpenAiLoginClient,
    private val mcp: McpManagerImpl,
    private val waitForCallbackDeadline: suspend (Duration) -> Unit,
) : AutoCloseable {
    public constructor(
        scope: CoroutineScope,
        credentials: BackendFileSystemAuthStore,
        openAi: OpenAiLoginClient,
        mcp: McpManagerImpl,
    ) : this(scope, credentials, openAi, mcp, { delay(it) })
    private val owner = scope.supervisorChildScope()
    private val mutex = Mutex()
    private val attempts = mutableMapOf<Long, Attempt>()
    private var nextId = 1L

    internal suspend fun closeAndJoin() {
        close()
        owner.cancelAndJoin()
    }

    public suspend fun start(target: OAuthTarget, redirectUri: String): OAuthAuthorization = accepted {
        validateRedirect(target, redirectUri)
        val attempt = mutex.withLock {
            require(attempts.values.none { it.target == target }) { "This target is already authorizing." }
            check(nextId < Long.MAX_VALUE) { "OAuth attempt identities are exhausted." }
            Attempt(nextId++, target, redirectUri).also { entry ->
                attempts[entry.id] = entry
                // Enter the try/finally before cancellation can observe this
                // entry; even cancellation during preparation releases its slot.
                entry.work = owner.async(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        when (target) {
                            is OAuthTarget.OpenAi -> {
                                val generation = credentials.beginLogin(target.source)
                                val prepared = prepareOpenAiLogin(openAi, redirectUri)
                                ready(entry, prepared.state, prepared.authorizationUrl, entry.code)
                                val tokens = prepared.exchangeCode(entry.code.await())
                                credentials.commitLogin(target.source, generation, tokens)
                            }
                            is OAuthTarget.Mcp -> mcp.loginWithPreparedCallback(target.serverName, redirectUri) { raw ->
                                val remote = raw as? RemoteMcpAttempt
                                    ?: error("The backend MCP manager requires listener-free attempts.")
                                require(remote.preparedConfiguration.client.redirectUri == redirectUri) {
                                    "The callback address does not match the MCP configuration."
                                }
                                ready(entry, remote.prepared.state, remote.authorizationUrl, remote.code)
                            }
                        }
                    } catch (failure: Throwable) {
                        entry.authorization.completeExceptionally(failure)
                        throw failure
                    } finally {
                        withContext(NonCancellable) {
                            mutex.withLock {
                                entry.expiry?.cancel()
                                if (attempts[entry.id] === entry) attempts.remove(entry.id)
                            }
                        }
                    }
                }
                entry.work.invokeOnCompletion { cause ->
                    if (cause != null) entry.authorization.completeExceptionally(cause)
                }
            }
        }
        attempt.authorization.await()
    }

    public suspend fun complete(id: Long, callbackUrl: String): Unit = accepted {
        val work = mutex.withLock {
            val attempt = attempts[id] ?: error("Unknown or completed OAuth attempt.")
            check(attempt.pending) { "This OAuth attempt is not waiting for a callback." }
            val result = parseCallback(attempt.redirectUri, requireNotNull(attempt.state), callbackUrl)
            attempt.pending = false
            attempt.expiry?.cancel()
            if (result == null) {
                attempt.work.cancel()
                error("Browser authorization was not completed.")
            }
            requireNotNull(attempt.delivery).complete(result)
            attempt.work
        }
        work.await()
    }

    public suspend fun cancel(id: Long): Unit = accepted {
        mutex.withLock {
            attempts[id]?.also {
                it.pending = false
                it.expiry?.cancel()
                it.work.cancel()
            }
        }
    }

    public suspend fun removeAuthentication(source: KodexAuthSource): Unit = accepted {
        mutex.withLock {
            attempts.values.filter { it.target == OAuthTarget.OpenAi(source) }.forEach {
                it.pending = false
                it.expiry?.cancel()
                it.work.cancel()
            }
        }
        credentials.remove(source)
    }

    public suspend fun logoutMcpServer(name: String): Unit = accepted { mcp.logout(name) }

    private suspend fun ready(
        attempt: Attempt,
        state: String,
        url: String,
        delivery: CompletableDeferred<String>,
    ) {
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            check(attempts[attempt.id] === attempt)
            attempt.state = state
            attempt.delivery = delivery
            attempt.pending = true
            attempt.expiry = owner.launch {
                waitForCallbackDeadline(10.minutes)
                mutex.withLock {
                    if (attempts[attempt.id] === attempt && attempt.pending) {
                        attempt.pending = false
                        attempt.work.cancel()
                    }
                }
            }
            attempt.authorization.complete(OAuthAuthorization(attempt.id, url))
        }
    }

    private suspend fun <R> accepted(block: suspend () -> R): R {
        currentCoroutineContext().ensureActive()
        owner.ensureActive()
        return owner.async { block() }.await()
    }

    override fun close() { owner.cancel() }
}

private class Attempt(val id: Long, val target: OAuthTarget, val redirectUri: String) {
    val authorization = CompletableDeferred<OAuthAuthorization>()
    val code = CompletableDeferred<String>()
    lateinit var work: Deferred<Unit>
    var pending = false
    var state: String? = null
    var delivery: CompletableDeferred<String>? = null
    var expiry: Job? = null
}

private fun validateRedirect(target: OAuthTarget, value: String) {
    val uri = Url(value)
    require(uri.protocol.name == "http" && uri.host in setOf("localhost", "127.0.0.1")) {
        "OAuth requires a supported loopback redirect."
    }
    require(uri.user == null && uri.password == null && uri.fragment.isEmpty() && uri.parameters.isEmpty()) {
        "The redirect must not contain user information, query parameters or a fragment."
    }
    require(uri.port in 1..65535) { "The redirect port is invalid." }
    if (target is OAuthTarget.OpenAi) {
        require(uri.host == "localhost" && uri.port in setOf(1455, 1457) && uri.encodedPath == "/auth/callback") {
            "The OpenAI callback address is unsupported."
        }
    }
}

/** Parses data only. Neither an arbitrary callback URL nor its redirect is fetched. */
private fun parseCallback(redirect: String, expectedState: String, callback: String): String? {
    val bound = Url(redirect)
    val result = Url(callback)
    require(result.protocol == bound.protocol && result.host == bound.host && result.port == bound.port &&
        result.encodedPath == bound.encodedPath && result.user == null && result.password == null &&
        result.fragment.isEmpty()) { "The callback address does not match this attempt." }
    val parameters = result.parameters
    require(parameters.getAll("state") == listOf(expectedState)) { "The OAuth state does not match this attempt." }
    val code = parameters.getAll("code")
    val error = parameters.getAll("error")
    require((code == null) != (error == null)) { "The OAuth response must contain one code or error." }
    if (error != null) {
        require(error.size == 1 && error.single().isNotBlank()) { "The OAuth error response is malformed." }
        return null
    }
    require(code?.size == 1 && code.single().isNotBlank()) { "The OAuth code response is malformed." }
    return code.single()
}
