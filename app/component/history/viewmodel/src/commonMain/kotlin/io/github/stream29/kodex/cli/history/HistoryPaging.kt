package io.github.stream29.kodex.cli.history

import io.github.stream29.kodex.agentstorage.contract.KodexAgentStorage

/**
 * Top-level items, not storage indexes or terminal rows. A Work Group counts as one item.
 * The retention budget includes both prefetch directions and at least one loading page.
 */
internal object HistoryPaging {
    const val PageSize = 64
    const val InitialLoadSize = 3 * PageSize
    const val PrefetchDistance = PageSize
    const val MaxSize = 4 * PageSize

    init {
        require(MaxSize >= PageSize + 2 * PrefetchDistance)
    }
}

internal suspend fun KodexAgentStorage.readHistoryPage(
    fromInclusive: Int,
    snapshotIndex: Int,
    size: Int = HistoryPaging.PageSize,
    newerThan: Int? = null,
): LoadedHistoryChunk {
    val items = mutableListOf<HistoryProjectionItem>()
    var cursor: Int? = fromInclusive
    while (cursor != null && items.size < size && (newerThan == null || cursor > newerThan)) {
        val from = cursor
        val remaining = size - items.size
        val chunk = readHistoryChunk(
            from, snapshotIndex,
            maxOpenItems = if (newerThan == null) remaining else minOf(remaining, from - newerThan),
        )
        items += chunk.items
        cursor = chunk.nextOlderIndex
        check(cursor == null || cursor < from) { "History cursor did not advance." }
    }
    return LoadedHistoryChunk(items, cursor)
}

internal suspend fun KodexAgentStorage.readNewerHistoryPage(
    afterExclusive: Int,
    snapshotIndex: Int,
): LoadedHistoryChunk? {
    var cursor = afterExclusive
    var result: LoadedHistoryChunk? = null
    while (cursor < snapshotIndex && (result?.items?.size ?: 0) < HistoryPaging.PageSize) {
        val chunk = readNewerHistoryChunk(
            cursor, snapshotIndex, maxOpenItems = HistoryPaging.PageSize - (result?.items?.size ?: 0),
        ) ?: break
        val newest = chunk.items.firstOrNull()?.newestStorageIndex ?: break
        check(newest > cursor) { "Newer History cursor did not advance." }
        val retained = result?.items.orEmpty().filter {
            it.newestStorageIndex < chunk.items.last().oldestStorageIndex
        }
        result = LoadedHistoryChunk(
            chunk.items + retained,
            if (result == null) chunk.nextOlderIndex else result.nextOlderIndex,
        )
        cursor = newest
    }
    return result
}

internal val HistoryProjectionItem.oldestStorageIndex: Int
    get() = when (this) {
        is HistoryProjectionItem.Stable -> descriptor.index
        is HistoryProjectionItem.WorkGroup -> indexRange.first
    }

internal val HistoryProjectionItem.newestStorageIndex: Int
    get() = when (this) {
        is HistoryProjectionItem.Stable -> descriptor.index
        is HistoryProjectionItem.WorkGroup -> indexRange.last
    }
