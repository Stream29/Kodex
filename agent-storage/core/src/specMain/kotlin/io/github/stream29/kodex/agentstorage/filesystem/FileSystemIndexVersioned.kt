@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentstorage.filesystem

import io.github.stream29.kodex.agentstorage.contract.MutableIndexVersioned
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Direct filesystem projection of one sparse timeline.
 *
 * Each stored change point is one `<index>.json` file in [directory]. This
 * class owns neither the directory nor a process lease and intentionally keeps
 * no in-memory index or value cache. A rebuildable `latest.json` accelerates
 * exact tail reads without replacing the numbered files as the source of truth.
 *
 * A failed or cancelled revert compensates its in-process file moves. Process
 * interruption is not repaired when the timeline is opened.
 */
public expect class FileSystemIndexVersioned<T>(
    directory: Path,
    serializer: KSerializer<T>,
    json: Json,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
) : MutableIndexVersioned<T> {
    override suspend fun latestIndex(): Int
    override suspend fun get(index: Int): T
    override suspend fun getExact(index: Int): T?
    override suspend fun floorToIndex(index: Int): Int?
    override suspend fun ceilToIndex(index: Int): Int?
    override suspend fun indexesIn(range: IntRange): List<Int>
    override suspend fun valuesIn(range: IntRange): List<Pair<Int, T>>
    override suspend fun set(index: Int, value: T)
    override suspend fun revert(untilExclusive: Int)

    /** Lists stored change-point indexes in ascending order. */
    public suspend fun storedIndexes(): List<Int>

    /**
     * Reads exactly the entry at [index] without resolving a sparse timeline
     * floor. Callers must establish that [index] is a stored change point.
     */
    public suspend fun getUnsafe(index: Int): T

    /**
     * Copies numbered entry files in `[from, until)` without decoding values.
     *
     * The destination receives rebased names.  This is intentionally limited
     * to timelines whose payload needs no transformation.
     */
    public suspend fun copyRangeRawTo(
        from: Int,
        until: Int,
        target: FileSystemIndexVersioned<T>,
        snapshotAtStart: Boolean = false,
    )

    /**
     * Writes exactly the entry at [index] without enforcing append-only order.
     * Callers must own the timeline and maintain its index invariants.
     */
    public suspend fun setUnsafe(index: Int, value: T)

    /**
     * Reconciles the rebuildable latest-index file with an exact tail already
     * established by an owning cache.
     *
     * The caller must own this timeline and prove that [index] is the greatest
     * stored index, or `-1` when the timeline is empty.
     */
    public suspend fun reconcileLatestIndexUnsafe(index: Int)

    /**
     * Reads only the rebuildable latest-index pointer without enumerating or
     * repairing this timeline.
     *
     * Returns `null` when the pointer is missing, malformed, or dangling.
     */
    public suspend fun latestIndexFromPointerOrNull(): Int?
}
