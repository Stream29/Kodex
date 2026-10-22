package io.github.stream29.kodex.rpc.client

import io.github.stream29.kodex.rpc.contract.SuspendMutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Adds an authoritative suspending CAS to an already initialized, caller-owned state projection.
 *
 * Reads and collection delegate to this StateFlow; successful CAS never writes its local value.
 * The caller owns initialization, subscriptions and invalidation checks in [compareAndSet].
 * This view creates no coroutine, cache or additional mutable state.
 */
public expect fun <T> StateFlow<T>.asSuspendMutableStateFlow(
    compareAndSet: suspend (expect: T, update: T) -> Boolean,
): SuspendMutableStateFlow<T>

/**
 * Updates through CAS, recomputing [function] after each comparison failure.
 *
 * [function] can run multiple times and must not perform non-repeatable side effects.
 * Only false comparisons retry, after a cancellable 50ms delay; exceptions escape unchanged.
 * Field-level settings conflict detection belongs to the editing layer, not this generic loop.
 */
public expect suspend inline fun <T> SuspendMutableStateFlow<T>.update(function: (T) -> T): Unit

/** Like [update], but returns the expected value from the successful CAS, not the local value. */
public expect suspend inline fun <T> SuspendMutableStateFlow<T>.getAndUpdate(function: (T) -> T): T

/** Like [update], but returns the proposed value from the successful CAS, not the local value. */
public expect suspend inline fun <T> SuspendMutableStateFlow<T>.updateAndGet(function: (T) -> T): T
