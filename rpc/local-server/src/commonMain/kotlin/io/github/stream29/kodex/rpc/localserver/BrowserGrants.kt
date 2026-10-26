package io.github.stream29.kodex.rpc.localserver

import io.github.stream29.kodex.backend.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

/** Bounded admission leases, not frontend sessions. Only authenticated browser heartbeats renew them. */
internal class BrowserGrants(clock: TimeSource = TimeSource.Monotonic) {
    private val start = clock.markNow()
    private fun now(): Long = start.elapsedNow().inWholeMilliseconds
    private class Grant(val origin: String, val directory: String, var touched: Long) {
        val peers = mutableSetOf<Job>()
    }
    private val mutex = Mutex()
    private val grants = mutableMapOf<String, Grant>()
    private fun sweep() {
        val expired = grants.filterValues { now() - it.touched >= 60_000 }.keys
        expired.forEach { key -> grants.remove(key)?.peers?.forEach { it.cancel() } }
    }
    suspend fun expire() = mutex.withLock { sweep() }
    suspend fun put(input: LocalBrowserGrant, directory: String) = mutex.withLock {
        sweep()
        require(input.protocol == LocalBrowserGrantProtocol)
        require(input.origin == canonicalLocalBrowserOrigin(input.origin))
        require(input.password.matches(Regex("[0-9a-f]{64}")))
        require(input.password !in grants) { "Browser credential already registered." }
        check(grants.size < 128) { "Browser admission capacity reached." }
        grants[input.password] = Grant(input.origin, directory, now())
    }
    suspend fun originAllowed(origin: String): Boolean = mutex.withLock {
        sweep()
        grants.values.any { it.origin == origin }
    }
    suspend fun renew(password: String, origin: String): Boolean = mutex.withLock {
        sweep()
        val grant = grants[password]?.takeIf { it.origin == origin } ?: return@withLock false
        grant.touched = now()
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
        grants.remove(password)?.peers?.forEach { it.cancel() }
        Unit
    }
}
