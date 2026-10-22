@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/** Holds [value] behind read sessions exposing [ImmutableType] and write sessions exposing [MutableType]. */
public expect class SafeRw<out ImmutableType, out MutableType : ImmutableType>(value: MutableType) {
    @PublishedApi
    internal val value: MutableType

    @PublishedApi
    internal val rwMutex: ReadWriteMutex
}

/**
 * Runs [block] with the immutable view while holding a shared read lock.
 *
 * The block may suspend or return non-locally. The lock is released on return, failure or cancellation.
 *
 * @throws CancellationException If acquiring the lock is cancelled or [block] propagates cancellation.
 * @throws Throwable If [block] fails; its failure propagates after releasing the lock.
 */
@OptIn(ExperimentalContracts::class)
public suspend inline fun <ImmutableType, T> SafeRw<ImmutableType, *>.readSession(
    block: suspend (ImmutableType) -> T,
): T {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    return rwMutex.reader.withLock {
        block(value)
    }
}

/**
 * Runs [block] with the mutable view while holding the exclusive write lock.
 *
 * The block may suspend or return non-locally. The lock is released on return, failure or cancellation.
 *
 * @throws CancellationException If acquiring the lock is cancelled or [block] propagates cancellation.
 * @throws Throwable If [block] fails; its failure propagates after releasing the lock.
 */
@OptIn(ExperimentalContracts::class)
public suspend inline fun <MutableType, T> SafeRw<*, MutableType>.writeSession(
    block: suspend (MutableType) -> T,
): T {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    return rwMutex.writer.withLock {
        block(value)
    }
}
