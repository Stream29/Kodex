package io.github.stream29.kodex.agentstorage.contract

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

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
    /** Last published index, or `-1` for an empty appendable timeline. */
    public suspend fun latestIndex(): Int

    /** Returns the value visible at [index]. */
    public suspend operator fun get(index: Int): T

    /** Returns the exact stored value at [index], or `null` if absent. */
    public suspend fun getExact(index: Int): T?

    /** Returns the greatest stored index less than or equal to [index]. */
    public suspend fun floorToIndex(index: Int): Int?

    /** Returns the smallest stored index greater than or equal to [index]. */
    public suspend fun ceilToIndex(index: Int): Int?

    /** Returns stored indexes in ascending order within inclusive [range]. */
    public suspend fun indexesIn(range: IntRange): List<Int>

    /** Returns stored values paired with their indexes within inclusive [range]. */
    public suspend fun valuesIn(range: IntRange): List<Pair<Int, T>>

    /** Emits stored indexes in ascending order, starting at [from]. */
    public fun indexes(from: Int = 0): Flow<Int> = defaultIndexes(from)

    /** Emits stored indexes in descending order, starting at or below [from]. */
    public fun indexesDescending(from: Int): Flow<Int> = defaultIndexesDescending(from)

    /** Emits exact values paired with ascending stored indexes. */
    public fun values(from: Int = 0): Flow<Pair<Int, T>> = defaultValues(from, descending = false)

    /** Emits exact values paired with descending stored indexes. */
    public fun valuesDescending(from: Int): Flow<Pair<Int, T>> = defaultValues(from, descending = true)
}

/** Returns the newest value published by this timeline. */
public suspend fun <T> IndexVersioned<T>.latestValue(): T =
    this[latestIndex()]

/**
 * Emits stored indexes in ascending order, starting at [from].
 *
 * Retained as a source-compatible import; implementations may override the member.
 */
@Suppress("EXTENSION_SHADOWED_BY_MEMBER")
public fun <T> IndexVersioned<T>.indexes(from: Int = 0): Flow<Int> = this.indexes(from)

private fun <T> IndexVersioned<T>.defaultIndexes(from: Int): Flow<Int> {
    val timeline = this
    return flow {
        require(from >= 0) { "Index lower bound $from must be non-negative." }
        val latest = timeline.latestIndex()
        if (from > latest) return@flow
        var lower = timeline.ceilToIndex(from) ?: return@flow
        var width = 1L
        while (lower <= latest) {
            val upper = minOf(
                latest.toLong(),
                lower.toLong() + width - 1,
            ).toInt()
            timeline.indexesIn(lower..upper).forEach { emit(it) }
            if (upper == latest) break
            lower = timeline.ceilToIndex(upper + 1) ?: break
            width = (width shl 1).coerceAtMost(32L)
        }
    }
}

/**
 * Emits stored indexes in descending order, starting at or below [from].
 *
 * Retained as a source-compatible import; implementations may override the member.
 */
@Suppress("EXTENSION_SHADOWED_BY_MEMBER")
public fun <T> IndexVersioned<T>.indexesDescending(from: Int): Flow<Int> = this.indexesDescending(from)

private fun <T> IndexVersioned<T>.defaultIndexesDescending(from: Int): Flow<Int> {
    val timeline = this
    return flow {
        require(from >= 0) { "Index upper bound $from must be non-negative." }
        val latest = timeline.latestIndex()
        if (latest < 0) return@flow
        var upper = timeline.floorToIndex(minOf(from, latest)) ?: return@flow
        var width = 1L
        while (upper >= 0) {
            val lower = maxOf(
                0L,
                upper.toLong() - width + 1,
            ).toInt()
            timeline.indexesIn(lower..upper)
                .asReversed()
                .forEach { emit(it) }
            if (lower == 0) break
            upper = timeline.floorToIndex(lower - 1) ?: break
            width = (width shl 1).coerceAtMost(32L)
        }
    }
}

/** Retained as a source-compatible import; implementations may override the member. */
@Suppress("EXTENSION_SHADOWED_BY_MEMBER")
public fun <T> IndexVersioned<T>.values(
    from: Int = 0,
): Flow<Pair<Int, T>> = this.values(from)

/** Retained as a source-compatible import; implementations may override the member. */
@Suppress("EXTENSION_SHADOWED_BY_MEMBER")
public fun <T> IndexVersioned<T>.valuesDescending(
    from: Int,
): Flow<Pair<Int, T>> = this.valuesDescending(from)

private fun <T> IndexVersioned<T>.defaultValues(
    from: Int,
    descending: Boolean,
): Flow<Pair<Int, T>> {
    val timeline = this
    return flow {
        val batch = mutableListOf<Int>()
        var capacity = 1
        suspend fun emitBatch() {
            if (batch.isEmpty()) return
            val expected = batch.toSet()
            val range = if (descending) batch.last()..batch.first() else batch.first()..batch.last()
            val values = timeline.valuesIn(range)
            val ordered = if (descending) values.asReversed() else values
            ordered.forEach { (index, value) ->
                if (index in expected) emit(index to value)
            }
            batch.clear()
        }
        val indexes = if (descending) timeline.indexesDescending(from) else timeline.indexes(from)
        indexes.collect { index ->
            batch += index
            if (batch.size == capacity) {
                emitBatch()
                capacity = (capacity * 2).coerceAtMost(32)
            }
        }
        emitBatch()
    }
}

public interface MutableIndexVersioned<T> : IndexVersioned<T> {
    /** Publishes [value] at an index greater than [latestIndex]. */
    public suspend operator fun set(index: Int, value: T)

    /** Removes every stored entry whose index is greater than or equal to [untilExclusive]. */
    public suspend fun revert(untilExclusive: Int)
}

/** Appends [value] at `latestIndex() + 1` and returns the published index. */
public suspend fun <T> MutableIndexVersioned<T>.append(value: T): Int {
    val index = latestIndex() + 1
    this[index] = value
    return index
}
