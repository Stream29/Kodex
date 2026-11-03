package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.backend.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One page's local resources. The caller's business RPC never passes through this object. */
internal interface FrontendCompanionResources {
    val capabilities: FrontendHostCapabilities
    suspend fun call(request: LocalFrontendHostRequest): LocalFrontendHostReply
    suspend fun closeAndJoin()
}

/** Exact-owner registry; authentication precedes dispatch, including idempotent resource closes. */
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
internal class FrontendCompanionOwners(
    private val scope: CoroutineScope,
    private val endpoint: BackendEndpoint,
    private val hostInstanceId: String,
    private val baseUrl: String,
    private val launchDirectory: String,
    private val executionEnvironmentId: String,
    private val newSecret: () -> String,
    private val nowMillis: () -> Long,
    private val backendOnline: suspend () -> Boolean,
    private val register: suspend (LocalBrowserGrant) -> String,
    private val revoke: suspend (LocalBrowserGrant) -> Unit,
    private val acquire: suspend (CoroutineScope, LocalFrontendOwner) -> FrontendCompanionResources,
    private val reservedSecrets: Set<String> = emptySet(),
) {
    private enum class Phase { Pending, Active, Closing }

    private class Entry(
        val owner: LocalFrontendOwner,
        val origin: String,
        val credential: String,
        val grant: LocalBrowserGrant,
        val job: Job,
    ) {
        val ready = CompletableDeferred<LocalFrontendBootstrap>()
        val stopped = CompletableDeferred<Result<Unit>>()
        lateinit var worker: Job
        var phase = Phase.Pending
        var resources: FrontendCompanionResources? = null
        var expiresAt = Long.MAX_VALUE
    }

    private val mutex = Mutex()
    private val entries = mutableMapOf<String, Entry>()
    private var ownerSequence = 0L
    private var closed = false
    private var shutdown: CompletableDeferred<Result<Unit>>? = null

    private fun secret(): String {
        val value = newSecret()
        check(value.matches(Regex("[0-9a-f]{64}")) &&
            value != endpoint.password && value !in reservedSecrets &&
            entries.values.none {
                it.owner.value == value || it.credential == value || it.grant.password == value
            }) { "Frontend server capability generation failed." }
        return value
    }

    suspend fun open(origin: String, directory: String): LocalFrontendBootstrap {
        expire()
        val entry = mutex.withLock {
            check(!closed && entries.size < LocalFrontendHostMaxOwners) { "Frontend server owner unavailable." }
            check(ownerSequence < Long.MAX_VALUE) { "Frontend server owner unavailable." }
            val owner = LocalFrontendOwner(
                secret().take(32) + (++ownerSequence).toString(16).padStart(32, '0'),
            )
            check(owner.value != endpoint.password && owner.value !in reservedSecrets &&
                entries.values.none {
                    it.owner.value == owner.value || it.credential == owner.value || it.grant.password == owner.value
                }) { "Frontend server identity generation failed." }
            val credential = secret()
            val grant = LocalBrowserGrant(LocalBrowserGrantProtocol, origin, secret(), directory)
            check(credential != grant.password && owner.value != credential && owner.value != grant.password) {
                "Frontend server capability generation failed."
            }
            val job = SupervisorJob(scope.coroutineContext[Job])
            Entry(owner, origin, credential, grant, job).also {
                entries[owner.value] = it
                // ATOMIC guarantees the finally even if the host cancels before dispatch.
                // This worker is a sibling, never a child of the resource Job it joins.
                it.worker = scope.launch(start = CoroutineStart.ATOMIC) { runOwner(it) }
            }
        }
        try {
            val bootstrap = entry.ready.await()
            currentCoroutineContext().ensureActive()
            return bootstrap
        } catch (failure: Throwable) {
            // await has prompt cancellation: ready may already contain a published owner.
            withContext(NonCancellable) {
                try { removeAndClose(entry) }
                catch (cleanup: Throwable) { suppress(failure, cleanup) }
            }
            throw failure
        }
    }

    private suspend fun runOwner(entry: Entry) {
        var resources: FrontendCompanionResources? = null
        var primary: Throwable? = null
        try {
            // Also forces an Unconfined worker to leave open's reservation critical section.
            mutex.withLock { check(!closed && entry.phase == Phase.Pending) }
            currentCoroutineContext().ensureActive()
            entry.job.ensureActive()
            val canonical = register(entry.grant)
            currentCoroutineContext().ensureActive()
            entry.job.ensureActive()
            resources = acquire(CoroutineScope(scope.coroutineContext + entry.job), entry.owner)
            currentCoroutineContext().ensureActive()
            entry.job.ensureActive()
            val descriptor = LocalFrontendHostDescriptor(
                LocalFrontendHostProtocol, hostInstanceId, endpoint.instanceId, entry.origin,
                baseUrl, entry.credential, entry.owner, executionEnvironmentId, launchDirectory,
                LocalFrontendHostOwnerLeaseMillis, resources.capabilities,
            )
            val bootstrap = LocalFrontendBootstrap(
                LocalFrontendBootstrapProtocol,
                LocalBrowserEndpoint(endpoint.instanceId, endpoint.port, entry.grant.password, endpoint.businessProtocol),
                canonical, descriptor,
            )
            check(LocalFrontendHostJson.encodeToString(
                LocalFrontendBootstrap.serializer(), bootstrap,
            ).encodeToByteArray().size <= LocalFrontendHostMaxFrameBytes) {
                "Frontend server bootstrap limit exceeded."
            }
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                entry.job.ensureActive()
                check(!closed && entry.phase == Phase.Pending)
                entry.resources = resources
                entry.expiresAt = nowMillis() + LocalFrontendHostOwnerLeaseMillis
                entry.phase = Phase.Active
                entry.ready.complete(bootstrap)
            }
            awaitCancellation()
        } catch (failure: Throwable) {
            primary = failure
        } finally {
            withContext(NonCancellable) {
                mutex.withLock { entry.phase = Phase.Closing }
                entry.job.cancel()
                var cleanupFailure: Throwable? = null
                suspend fun cleanup(action: suspend () -> Unit) {
                    try { action() }
                    catch (failure: Throwable) {
                        if (cleanupFailure == null) cleanupFailure = failure
                        else suppress(requireNotNull(cleanupFailure), failure)
                    }
                }
                cleanup { resources?.closeAndJoin() }
                cleanup { entry.job.join() }
                // Revoke even a lost register reply, but never discover/revive another backend.
                cleanup { withTimeout(3_000) { revoke(entry.grant) } }
                cleanupFailure?.let { failure ->
                    primary?.let { suppress(it, failure) }
                }
                val result = cleanupFailure?.let { Result.failure<Unit>(it) } ?: Result.success(Unit)
                mutex.withLock {
                    entry.stopped.complete(result)
                    entries.remove(entry.owner.value)
                    if (!entry.ready.isCompleted) entry.ready.completeExceptionally(
                        primary ?: cleanupFailure ?: CancellationException("Frontend server owner closed."),
                    )
                }
            }
        }
    }

    /** Null means unauthorized; do not return another owner's descriptor, status or existence. */
    suspend fun call(origin: String, call: LocalFrontendHostCall): LocalFrontendHostResult? {
        expire()
        val entry = mutex.withLock {
            entries[call.owner.value]?.takeIf {
                it.resources != null && call.protocol == LocalFrontendHostProtocol &&
                    call.hostInstanceId == hostInstanceId &&
                    call.credential == it.credential &&
                    origin == it.origin
            }
        } ?: return null
        val reply = when (call.request) {
            LocalFrontendHostRequest.CloseOwner -> {
                removeAndClose(entry)
                LocalFrontendHostReply.OwnerClosed
            }
            LocalFrontendHostRequest.RenewOwner -> {
                if (mutex.withLock { closed || entry.phase != Phase.Active }) {
                    LocalFrontendHostReply.Failure(LocalFrontendHostFailure.OwnerClosed)
                } else if (!entry.job.isActive) {
                    removeAndClose(entry)
                    LocalFrontendHostReply.Failure(LocalFrontendHostFailure.OwnerClosed)
                } else if (!backendOnline()) {
                    removeAndClose(entry)
                    LocalFrontendHostReply.Failure(LocalFrontendHostFailure.BackendOffline)
                } else {
                    val renewed = mutex.withLock {
                        if (entries[entry.owner.value] !== entry || closed || entry.phase != Phase.Active ||
                            entry.expiresAt <= nowMillis()) false
                        else {
                            entry.expiresAt = nowMillis() + LocalFrontendHostOwnerLeaseMillis
                            true
                        }
                    }
                    if (renewed) LocalFrontendHostReply.OwnerRenewed(
                        LocalFrontendHostOwnerLeaseMillis, requireNotNull(entry.resources).capabilities,
                    ) else LocalFrontendHostReply.Failure(LocalFrontendHostFailure.OwnerClosed)
                }
            }
            else -> try {
                val active = mutex.withLock { !closed && entry.phase == Phase.Active }
                if (!active) LocalFrontendHostReply.Failure(LocalFrontendHostFailure.OwnerClosed)
                else {
                    currentCoroutineContext().ensureActive()
                    val operation = CoroutineScope(currentCoroutineContext() + entry.job).async {
                        requireNotNull(entry.resources).call(call.request)
                    }
                    try { operation.await() }
                    finally { withContext(NonCancellable) { operation.cancelAndJoin() } }
                }
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                LocalFrontendHostReply.Failure(LocalFrontendHostFailure.OwnerClosed)
            } catch (_: Exception) {
                LocalFrontendHostReply.Failure(LocalFrontendHostFailure.ServiceFailed)
            }
        }
        return LocalFrontendHostResult(LocalFrontendHostProtocol, hostInstanceId, entry.owner, reply)
    }

    suspend fun expire() {
        val expired = mutex.withLock {
            val now = nowMillis()
            entries.values.filter { it.phase == Phase.Active && it.expiresAt <= now }.also { values ->
                values.forEach { it.phase = Phase.Closing }
            }
        }
        closeAll(expired)
    }

    suspend fun closeAndJoin() = withContext(NonCancellable) {
        var all: List<Entry>? = null
        val completion = mutex.withLock {
            shutdown ?: CompletableDeferred<Result<Unit>>().also { completion ->
                shutdown = completion
                closed = true
                all = entries.values.toList().also { values ->
                    values.forEach { it.phase = Phase.Closing }
                }
            }
        }
        all?.let { values ->
            completion.complete(runCatching { closeAll(values) })
        }
        completion.await().getOrThrow()
    }

    private suspend fun removeAndClose(entry: Entry) = withContext(NonCancellable) {
        mutex.withLock { entry.phase = Phase.Closing }
        closeAll(listOf(entry))
    }

    private suspend fun closeAll(values: List<Entry>): Unit = withContext(NonCancellable) {
        // Stop every owner before any cleanup barrier, including pending acquisitions.
        values.forEach { it.job.cancel() }
        values.forEach { it.worker.cancel() }
        var primary: Throwable? = null
        for (entry in values) {
            val failure = entry.stopped.await().exceptionOrNull()
            entry.worker.join()
            if (failure != null) {
                mutex.withLock {
                    if (primary == null) primary = failure else suppress(requireNotNull(primary), failure)
                }
            }
        }
        if (primary != null) throw requireNotNull(primary)
    }

    private fun suppress(primary: Throwable, cleanup: Throwable) {
        if (primary !== cleanup && primary.suppressedExceptions.none { it === cleanup }) {
            primary.addSuppressed(cleanup)
        }
    }
}
