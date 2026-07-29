package io.github.stream29.codex.lite.cli.components

import androidx.compose.runtime.Composable

/** Marks builders which declare lazy list items without composing them. */
@DslMarker
public annotation class LazyListScopeMarker

/** Receiver used by [LazyColumn] to describe lazily composed items. */
@LazyListScopeMarker
public interface LazyListScope {
    /**
     * Adds one item.
     *
     * @param key `null` gives this item index identity. A non-null stable key preserves item
     * identity across insertions and reorderings.
     * @param contentType `null` selects the default subcomposition reuse class.
     */
    public fun item(
        key: Any? = null,
        contentType: Any? = null,
        content: @Composable () -> Unit,
    )

    /**
     * Adds [count] indexed items.
     *
     * @param key `null` gives every item its current global index as identity.
     * @param contentType `null` selects the default subcomposition reuse class for every item. A
     * non-null factory may itself return `null` for items in that default class.
     */
    public fun items(
        count: Int,
        key: ((index: Int) -> Any)? = null,
        contentType: ((index: Int) -> Any?)? = null,
        itemContent: @Composable (index: Int) -> Unit,
    )
}

/**
 * Adds all [items] to this lazy list.
 *
 * @param key `null` uses each item's current global index as identity.
 * @param contentType `null` places every item in the default subcomposition reuse class. A
 * non-null factory may itself return `null` for items in that default class.
 */
public fun <T> LazyListScope.items(
    items: List<T>,
    key: ((item: T) -> Any)? = null,
    contentType: ((item: T) -> Any?)? = null,
    itemContent: @Composable (item: T) -> Unit,
) {
    items(
        count = items.size,
        key = key?.let { keyFactory -> { index -> keyFactory(items[index]) } },
        contentType = contentType?.let { typeFactory -> { index -> typeFactory(items[index]) } },
    ) { index ->
        itemContent(items[index])
    }
}

internal class LazyListScopeImpl : LazyListScope {
    private val intervals = mutableListOf<LazyListInterval>()
    private var itemCount = 0

    override fun item(
        key: Any?,
        contentType: Any?,
        content: @Composable () -> Unit,
    ) {
        val keyFactory = if (key == null) {
            LazyKeyFactory.Default
        } else {
            LazyKeyFactory.Explicit { key }
        }
        addInterval(
            count = 1,
            keyFactory = keyFactory,
            contentTypeFactory = { contentType },
            itemContent = { content() },
        )
    }

    override fun items(
        count: Int,
        key: ((index: Int) -> Any)?,
        contentType: ((index: Int) -> Any?)?,
        itemContent: @Composable (index: Int) -> Unit,
    ) {
        require(count >= 0) { "Lazy list item count cannot be negative." }
        if (count == 0) return
        addInterval(
            count = count,
            keyFactory = key?.let(LazyKeyFactory::Explicit) ?: LazyKeyFactory.Default,
            contentTypeFactory = contentType ?: DefaultContentTypeFactory,
            itemContent = itemContent,
        )
    }

    fun build(): LazyItemProvider = LazyItemProvider(intervals.toList(), itemCount)

    private fun addInterval(
        count: Int,
        keyFactory: LazyKeyFactory,
        contentTypeFactory: (Int) -> Any?,
        itemContent: @Composable (Int) -> Unit,
    ) {
        val updatedCount = itemCount.toLong() + count
        require(updatedCount <= Int.MAX_VALUE) { "Lazy list item count exceeds Int.MAX_VALUE." }
        intervals += LazyListInterval(
            startIndex = itemCount,
            count = count,
            keyFactory = keyFactory,
            contentTypeFactory = contentTypeFactory,
            itemContent = itemContent,
        )
        itemCount = updatedCount.toInt()
    }
}

internal class LazyItemProvider(
    private val intervals: List<LazyListInterval>,
    val itemCount: Int,
) {
    private val keys: List<Any> = List(itemCount) { index ->
        val interval = intervalAt(index)
        interval.keyFactory.key(index - interval.startIndex, index)
    }
    private val indicesByKey = HashMap<Any, Int>(itemCount)

    init {
        keys.forEachIndexed { index, key ->
            require(indicesByKey.put(key, index) == null) {
                "Lazy list key $key is used more than once."
            }
        }
    }

    fun keyAt(index: Int): Any = keys[index]

    fun indexOfKey(key: Any): Int? = indicesByKey[key]

    /** @return `null` when this item belongs to the default subcomposition reuse class. */
    fun contentTypeAt(index: Int): Any? {
        val interval = intervalAt(index)
        return interval.contentTypeFactory(index - interval.startIndex)
    }

    @Composable
    fun Item(index: Int) {
        val interval = intervalAt(index)
        interval.itemContent(index - interval.startIndex)
    }

    private fun intervalAt(index: Int): LazyListInterval {
        require(index in 0 until itemCount) { "Lazy list index $index is out of bounds for $itemCount items." }
        var low = 0
        var high = intervals.lastIndex
        while (low <= high) {
            val middle = (low + high).ushr(1)
            val interval = intervals[middle]
            when {
                index < interval.startIndex -> high = middle - 1
                index >= interval.startIndex + interval.count -> low = middle + 1
                else -> return interval
            }
        }
        error("No lazy list interval contains index $index.")
    }
}

internal class LazyListInterval(
    val startIndex: Int,
    val count: Int,
    val keyFactory: LazyKeyFactory,
    val contentTypeFactory: (Int) -> Any?,
    val itemContent: @Composable (Int) -> Unit,
)

internal sealed interface LazyKeyFactory {
    fun key(localIndex: Int, globalIndex: Int): Any

    data object Default : LazyKeyFactory {
        override fun key(localIndex: Int, globalIndex: Int): Any = DefaultLazyKey(globalIndex)
    }

    class Explicit(private val factory: (Int) -> Any) : LazyKeyFactory {
        override fun key(localIndex: Int, globalIndex: Int): Any = factory(localIndex)
    }
}

private data class DefaultLazyKey(val index: Int)

private val DefaultContentTypeFactory: (Int) -> Any? = { null }
