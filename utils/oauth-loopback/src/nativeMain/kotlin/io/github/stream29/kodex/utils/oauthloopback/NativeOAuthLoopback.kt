package io.github.stream29.kodex.utils.oauthloopback

import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

public actual suspend fun openOAuthLoopbackListener(
    scope: CoroutineScope,
    redirectCandidates: List<String>,
): OAuthLoopbackListener = openLoopback(scope, redirectCandidates)

internal interface LoopbackEngine {
    suspend fun start(): Int
    suspend fun stop()
}

internal typealias LoopbackEngineFactory =
    (CoroutineScope, Url, suspend (Parameters, String) -> Boolean) -> LoopbackEngine

internal fun parseRedirect(candidate: String): Url {
    val url = try {
        Url(candidate)
    } catch (_: Throwable) {
        throw IllegalArgumentException("Invalid local callback redirect.")
    }
    require(
        url.protocol.name == "http" && url.host in setOf("localhost", "127.0.0.1") &&
            url.parameters.isEmpty() && url.fragment.isEmpty() && url.user == null && url.password == null,
    ) { "Invalid local callback redirect." }
    return url
}

internal fun callbackBindPort(candidate: String, url: Url): Int {
    // Ktor uses zero as its unspecified-port sentinel, even for an explicit ":0".
    val authority = candidate.substringAfter("://").substringBefore('/')
    return if (authority.substringAfterLast(':').toIntOrNull() == 0) 0 else url.port
}

internal suspend fun openLoopback(
    scope: CoroutineScope,
    candidates: List<String>,
    engineFactory: LoopbackEngineFactory? = null,
): OAuthLoopbackListener {
    val parent = requireNotNull(scope.coroutineContext[Job]) { "Local callback owner requires a Job." }
    currentCoroutineContext().ensureActive()
    scope.coroutineContext.ensureActive()
    for (candidate in candidates) {
        currentCoroutineContext().ensureActive()
        scope.coroutineContext.ensureActive()
        val url = parseRedirect(candidate)
        val owner = SupervisorJob(parent)
        val engineOwner = SupervisorJob(parent)
        val ready = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val listener = NativeLoopbackListener(owner, stopped)
        // Engine children can report bind failures independently of startSuspend.
        val errors = CoroutineExceptionHandler { _, _ ->
            ready.completeExceptionally(engineFailure())
            listener.fail()
            owner.cancel()
        }
        val resourceScope = CoroutineScope(scope.coroutineContext + owner + errors)
        val engineScope = CoroutineScope(scope.coroutineContext + engineOwner + errors)
        resourceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var engine: LoopbackEngine? = null
            try {
                currentCoroutineContext().ensureActive()
                engine = engineFactory?.invoke(resourceScope, url, listener::receive)
                    ?: CioLoopbackEngine(engineScope, url, listener::claim, callbackBindPort(candidate, url))
                val port = engine.start()
                listener.setRedirect(URLBuilder(candidate).apply { this.port = port }.buildString())
                ready.complete(Unit)
                awaitCancellation()
            } catch (_: CancellationException) {
                ready.cancel(CancellationException("Local callback listener cancelled."))
            } catch (_: Throwable) {
                ready.completeExceptionally(engineFailure())
                listener.fail()
            } finally {
                withContext(NonCancellable) {
                    try {
                        engine?.stop()
                        stopped.complete(Unit)
                    } catch (_: Throwable) {
                        stopped.completeExceptionally(IllegalStateException("Local callback cleanup failed."))
                    } finally {
                        engineOwner.cancelAndJoin()
                        listener.cancelCallback()
                        ready.cancel(CancellationException("Local callback listener closed."))
                        owner.cancel()
                    }
                }
            }
        }
        try {
            ready.await()
            currentCoroutineContext().ensureActive()
            owner.ensureActive()
            return listener
        } catch (failure: Throwable) {
            try {
                listener.closeAndJoin()
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
                // A failed stop is not evidence that the address is safe to reuse.
                throw failure
            }
            // A failed CIO child can cancel startup without cancelling either caller or owner.
            currentCoroutineContext().ensureActive()
            scope.coroutineContext.ensureActive()
        }
    }
    throw IllegalStateException("Unable to bind a supported local callback listener.")
}

private fun engineFailure(): IllegalStateException =
    IllegalStateException("Local callback listener failed.")

internal class NativeLoopbackListener(
    private val owner: Job,
    private val stopped: CompletableDeferred<Unit>,
) : OAuthLoopbackListener {
    private val callback = CompletableDeferred<String>()
    private val waitClaimed = CompletableDeferred<Unit>()
    private val gate = Mutex()
    private var expectedState: String? = null
    private var valuePolicy = OAuthCallbackValuePolicy.AllowEmpty
    private var admit: suspend () -> Boolean = { true }
    private var claimed = false
    private var closing = false
    private val responseFinished = CompletableDeferred<Unit>()
    private var redirect: String = ""

    override val redirectUri: String get() = redirect

    suspend fun setRedirect(value: String) {
        gate.withLock { redirect = value }
    }

    override suspend fun armState(state: String, valuePolicy: OAuthCallbackValuePolicy) =
        armStateWithAdmission(state, valuePolicy) { true }

    override suspend fun armStateWithAdmission(
        state: String,
        valuePolicy: OAuthCallbackValuePolicy,
        admit: suspend () -> Boolean,
    ) {
        gate.withLock {
            currentCoroutineContext().ensureActive()
            check(owner.isActive && !closing && !callback.isCompleted && !claimed) { "Local callback listener is no longer available." }
            expectedState = state
            this.valuePolicy = valuePolicy
            this.admit = admit
        }
    }

    suspend fun receive(query: Parameters, rawQuery: String): Boolean {
        val complete = claim(query, rawQuery) ?: return false
        complete()
        return true
    }

    suspend fun claim(query: Parameters, rawQuery: String): (() -> Unit)? = gate.withLock {
        val expected = expectedState
        val valid = owner.isActive && !closing && !claimed && !callback.isCompleted &&
            expected != null && query.getAll("state") == listOf(expected) &&
            listOf("code", "error").all { query.getAll(it).orEmpty().size <= 1 } &&
            ((query["code"] != null) xor (query["error"] != null)) &&
            (valuePolicy == OAuthCallbackValuePolicy.AllowEmpty || !(query["code"] ?: query["error"]).isNullOrBlank())
        if (!valid) return@withLock null
        if (!admit()) return@withLock null
        claimed = true
        val value = "$redirect?$rawQuery"
        callback.complete(value)
        return@withLock { responseFinished.complete(Unit) }
    }

    override suspend fun awaitCallback(): String {
        check(waitClaimed.complete(Unit)) { "Local callback wait already claimed." }
        return callback.await()
    }

    fun fail() {
        callback.completeExceptionally(engineFailure())
    }

    fun cancelCallback() {
        callback.cancel(CancellationException("Local callback listener closed."))
    }

    override suspend fun closeAndJoin() {
        withContext(NonCancellable) {
            if (!owner.isActive) {
                owner.join()
                stopped.await()
                return@withContext
            }
            val pendingResponse = gate.withLock {
                closing = true
                claimed
            }
            if (pendingResponse) withTimeoutOrNull(2.seconds) { responseFinished.await() }
            cancelCallback()
            owner.cancel()
            owner.join()
            stopped.await()
        }
    }
}
