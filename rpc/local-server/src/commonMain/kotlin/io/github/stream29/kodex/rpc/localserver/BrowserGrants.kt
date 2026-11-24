package io.github.stream29.kodex.rpc.localserver

import io.github.stream29.kodex.backend.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

private const val GrantLifetimeMillis = 60_000L

/** Bounded admission leases, not frontend sessions. Only authenticated browser heartbeats renew them. */
internal class BrowserGrants(clock: TimeSource = TimeSource.Monotonic) {
    private val start = clock.markNow()
    private fun now(): Long = start.elapsedNow().inWholeMilliseconds
    private class Grant(val origin: String, val directory: String, var expiresAt: Long) {
        val peers = mutableSetOf<Job>()
    }
    private val mutex = Mutex()
    private val grants = mutableMapOf<String, Grant>()
    private val nextExpiry = MutableStateFlow<Long?>(null)

    /** Monotonic milliseconds from this registry's origin; null when empty. */
    val earliestExpiry = nextExpiry.asStateFlow()

    private fun publishNextExpiry() {
        nextExpiry.value = grants.values.minOfOrNull { it.expiresAt }
    }

    private fun sweep() {
        if (grants.isEmpty()) return
        val current = now()
        val expired = grants.filterValues { current >= it.expiresAt }.keys
        expired.forEach { key -> grants.remove(key)?.peers?.forEach { it.cancel() } }
        if (expired.isNotEmpty()) publishNextExpiry()
    }
    suspend fun expire() = mutex.withLock { sweep() }

    suspend fun monitorExpiry() {
        earliestExpiry.collectLatest { deadline ->
            if (deadline == null) return@collectLatest
            while (true) {
                val remaining = deadline - now()
                if (remaining <= 0L) {
                    expire()
                    return@collectLatest
                }
                delay(remaining)
            }
        }
    }

    suspend fun put(input: LocalBrowserGrant, directory: String) = mutex.withLock {
        sweep()
        require(input.protocol == LocalBrowserGrantProtocol)
        require(input.origin == canonicalLocalBrowserOrigin(input.origin))
        require(input.password.matches(Regex("[0-9a-f]{64}")))
        require(input.password !in grants) { "Browser credential already registered." }
        check(grants.size < 128) { "Browser admission capacity reached." }
        grants[input.password] = Grant(input.origin, directory, now() + GrantLifetimeMillis)
        publishNextExpiry()
    }
    suspend fun originAllowed(origin: String): Boolean = mutex.withLock {
        sweep()
        grants.values.any { it.origin == origin }
    }
    suspend fun renew(password: String, origin: String): Boolean = mutex.withLock {
        sweep()
        val grant = grants[password]?.takeIf { it.origin == origin } ?: return@withLock false
        grant.expiresAt = now() + GrantLifetimeMillis
        publishNextExpiry()
        true
    }
    suspend fun attach(password: String, origin: String, requested: String?, peer: Job): String? = mutex.withLock {
        sweep()
        val grant = grants[password]?.takeIf { it.origin == origin && it.directory == requested }
            ?: return@withLock null
        grant.peers.add(peer)
        grant.directory
    }
    suspend fun detach(password: String, peer: Job) = mutex.withLock {
        grants[password]?.peers?.remove(peer)
        Unit
    }
    suspend fun revoke(password: String) = mutex.withLock {
        val grant = grants.remove(password) ?: return@withLock
        grant.peers.forEach { it.cancel() }
        publishNextExpiry()
    }
}
