package io.github.stream29.kodex.cli.history

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
