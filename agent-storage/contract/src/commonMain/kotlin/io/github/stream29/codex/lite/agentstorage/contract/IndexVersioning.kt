package io.github.stream29.codex.lite.agentstorage.contract

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow

/**
 * Index-addressed timeline.
 *
 * Implementations may be sparse. For sparse step-function timelines,
 * [nextIndex] returns change points and [get] returns the value active at the
 * requested index. For example, if stored indexes are `0, 2, 5`, [get] returns
 * the same value for indexes in `[0, 2)`, another value for `[2, 5)`, and
 * another value for indexes from `5` onward.
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
     * Returns the first stored index greater than or equal to [from].
     *
     * @param from Inclusive lower bound.
     * @return `null` when this timeline has no stored index greater than or
     * equal to [from].
     */
    public suspend fun nextIndex(from: Int): Int?
}

/**
 * Returns stored indexes in ascending order.
 *
 * @param from Inclusive lower bound for returned indexes.
 */
public fun <T> IndexVersioned<T>.indexes(from: Int = 0): Flow<Int> = flow {
    var cursor = from
    while (true) {
        val next = nextIndex(cursor) ?: break
        emit(next)
        cursor = next + 1
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
    target: MutableIndexVersioned<T>
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
}

/**
 * Appends [value] at `latestIndex() + 1` and returns the published index.
 */
public suspend fun <T> MutableIndexVersioned<T>.append(value: T): Int {
    this[latestIndex() + 1] = value
    return latestIndex()
}
