package io.github.stream29.kodex.rpc.client

import io.github.stream29.kodex.rpc.contract.SuspendMutableStateFlow
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow

/**
 * Adds an authoritative suspending CAS to an already initialized, caller-owned state projection.
 *
 * Reads and collection delegate to this StateFlow; successful CAS never writes its local value.
 * The caller owns initialization, subscriptions and invalidation checks in [compareAndSet].
 * This view creates no coroutine, cache or additional mutable state.
 */
public fun <T> StateFlow<T>.asSuspendMutableStateFlow(
    compareAndSet: suspend (expect: T, update: T) -> Boolean,
): SuspendMutableStateFlow<T> = DelegatingSuspendMutableStateFlow(this, compareAndSet)

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class DelegatingSuspendMutableStateFlow<T>(
    state: StateFlow<T>,
    private val operation: suspend (T, T) -> Boolean,
) : SuspendMutableStateFlow<T>, StateFlow<T> by state {
    override suspend fun compareAndSet(expect: T, update: T): Boolean = operation(expect, update)
}

@PublishedApi
internal const val CAS_RETRY_DELAY_MILLIS: Long = 50

/**
 * Updates through CAS, recomputing [function] after each comparison failure.
 *
 * [function] can run multiple times and must not perform non-repeatable side effects.
 * Only false comparisons retry, after a cancellable 50ms delay; exceptions escape unchanged.
 * Field-level settings conflict detection belongs to the editing layer, not this generic loop.
 */
public suspend inline fun <T> SuspendMutableStateFlow<T>.update(function: (T) -> T) {
    while (true) {
        currentCoroutineContext().ensureActive()
        val expect = value
        val update = function(expect)
        if (compareAndSet(expect, update)) return
        delay(CAS_RETRY_DELAY_MILLIS)
    }
}

/** Like [update], but returns the expected value from the successful CAS, not the local value. */
public suspend inline fun <T> SuspendMutableStateFlow<T>.getAndUpdate(function: (T) -> T): T {
    while (true) {
        currentCoroutineContext().ensureActive()
        val expect = value
        val update = function(expect)
        if (compareAndSet(expect, update)) return expect
        delay(CAS_RETRY_DELAY_MILLIS)
    }
}

/** Like [update], but returns the proposed value from the successful CAS, not the local value. */
public suspend inline fun <T> SuspendMutableStateFlow<T>.updateAndGet(function: (T) -> T): T {
    while (true) {
        currentCoroutineContext().ensureActive()
        val expect = value
        val update = function(expect)
        if (compareAndSet(expect, update)) return update
        delay(CAS_RETRY_DELAY_MILLIS)
    }
}
