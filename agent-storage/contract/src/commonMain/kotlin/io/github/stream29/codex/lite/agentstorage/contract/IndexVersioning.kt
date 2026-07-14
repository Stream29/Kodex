package io.github.stream29.codex.lite.agentstorage.contract

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * Index-addressed timeline.
 *
 * Implementations may be sparse. [floorToIndex] and [ceilToIndex] locate stored
 * change points; [get] returns the value active at the requested index. For
 * example, if stored indexes are `0, 2, 5`, [get] returns the same value for
 * indexes in `[0, 2)`, another value for `[2, 5)`, and another value for
 * indexes from `5` onward.
 *
 * Append-only logs may also implement this contract by treating every item
 * index as a change point. Such implementations must make every index from
 * `0` through [latestIndex] readable.
 *
 * Calling [get] for an index before the first stored index is invalid.
 */
public interface IndexVersioned<T> {
    /**
     * Last published index visible to readers.
     *
     * Empty appendable timelines should return `-1` so [append] writes index
     * `0` as the first entry.
     */
    public suspend fun latestIndex(): Int

    /**
     * Returns the value visible at [index].
     */
    public suspend operator fun get(index: Int): T

    /**
     * Returns the greatest stored index less than or equal to [index].
     *
     * @return `null` when this timeline has no stored index at or before
     * [index].
     */
    public suspend fun floorToIndex(index: Int): Int?

    /**
     * Returns the smallest stored index greater than or equal to [index].
     *
     * @return `null` when this timeline has no stored index at or after
     * [index].
     */
    public suspend fun ceilToIndex(index: Int): Int?
}

/**
 * Returns the first stored index strictly after [index].
 */
public suspend fun <T> IndexVersioned<T>.nextIndex(index: Int): Int? {
    return if (index == Int.MAX_VALUE) null else ceilToIndex(index + 1)
}

/**
 * Returns the first stored index strictly before [index].
 */
public suspend fun <T> IndexVersioned<T>.prevIndex(index: Int): Int? {
    return if (index == Int.MIN_VALUE) null else floorToIndex(index - 1)
}

/**
 * Returns stored indexes in ascending order.
 *
 * @param from Inclusive lower bound for returned indexes.
 */
public fun <T> IndexVersioned<T>.indexes(from: Int = 0): Flow<Int> = flow {
    require(from >= 0) { "Index lower bound $from must be non-negative." }
    var index = ceilToIndex(from)
    while (index != null) {
        emit(index)
        index = nextIndex(index)
    }
}

/**
 * Returns stored indexes in descending order.
 *
 * @param from Inclusive upper bound for returned indexes.
 */
public fun <T> IndexVersioned<T>.indexesDescending(from: Int): Flow<Int> = flow {
    require(from >= 0) { "Index upper bound $from must be non-negative." }
    var index = floorToIndex(from)
    while (index != null) {
        emit(index)
        index = prevIndex(index)
    }
}

/**
 * Copies visible entries into an empty mutable timeline.
 *
 * This copies stored indexes lower than [until]. For append-only logs, [until]
 * is the exclusive item boundary. For sparse timelines, callers must ensure
 * this boundary matches their intended fork semantics.
 *
 * @param until Exclusive upper bound.
 */
public suspend fun <T> IndexVersioned<T>.forkTo(
    until: Int,
    target: MutableIndexVersioned<T>,
) {
    require(target.indexes().count() == 0) { "Only an empty target can be forked to." }
    this.indexes().filter { it < until }.collect { target[it] = this[it] }
}

public interface MutableIndexVersioned<T> : IndexVersioned<T> {
    /**
     * Publishes [value] at [index].
     *
     * This operation is append-only. [index] must be greater than
     * [latestIndex]; existing indexes must not be overwritten.
     */
    public suspend operator fun set(index: Int, value: T)

    /**
     * Removes every stored entry whose index is greater than or equal to
     * [untilExclusive].
     *
     * This is a suffix-only rollback operation. Implementations must either
     * remove the complete suffix or fail without changing the timeline.
     * Passing a boundary after the current tail is a no-op.
     *
     * @param untilExclusive First stored index to remove.
     */
    public suspend fun revert(untilExclusive: Int)
}

/**
 * Runs one externally serialized append transaction on this timeline.
 *
 * If [block] fails or is cancelled, every entry appended after the transaction
 * started is removed before the original failure is rethrown. [block] must not
 * revert entries that existed before the transaction.
 */
public suspend inline fun <R> MutableIndexVersioned<*>.transaction(block: () -> R): R {
    val untilExclusive = latestIndex() + 1
    return try {
        block()
    } catch (failure: Throwable) {
        try {
            withContext(NonCancellable) {
                revert(untilExclusive)
            }
        } catch (rollbackFailure: Throwable) {
            failure.addSuppressed(rollbackFailure)
        }
        throw failure
    }
}

/**
 * Appends [value] at `latestIndex() + 1` and returns the published index.
 */
public suspend fun <T> MutableIndexVersioned<T>.append(value: T): Int {
    this[latestIndex() + 1] = value
    return latestIndex()
}
