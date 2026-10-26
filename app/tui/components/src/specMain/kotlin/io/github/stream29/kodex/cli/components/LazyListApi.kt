@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable

/** Marks builders which declare lazy list items without composing them. */
@DslMarker
public annotation class LazyListScopeMarker

/** Receiver used by `LazyColumn` to describe lazily composed items. */
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
public expect fun <T> LazyListScope.items(
    items: List<T>,
    key: ((item: T) -> Any)? = null,
    contentType: ((item: T) -> Any?)? = null,
    itemContent: @Composable (item: T) -> Unit,
): Unit

/** Geometry of one currently visible lazy item, measured in terminal rows. */
@Immutable
public data class LazyListItemInfo(
    public val index: Int,
    public val key: Any,
    public val offset: Int,
    public val size: Int,
)

/** Immutable projection of the latest completed `LazyColumn` measure pass. */
@Immutable
public data class LazyListLayoutInfo(
    public val visibleItemsInfo: List<LazyListItemInfo>,
    public val viewportStartOffset: Int,
    public val viewportEndOffset: Int,
    public val totalItemsCount: Int,
) {
    public val viewportSize: Int
        get() = viewportEndOffset - viewportStartOffset
}

/** Scroll position and measured viewport state owned independently of one `LazyColumn` call. */
@Stable
public expect class LazyListState(
    initialFirstVisibleItemIndex: Int = 0,
    initialFirstVisibleItemScrollOffset: Int = 0,
) : ScrollableState {
    public val firstVisibleItemIndex: Int

    public val firstVisibleItemScrollOffset: Int

    public val layoutInfo: LazyListLayoutInfo

    public override val canScrollBackward: Boolean

    public override val canScrollForward: Boolean

    public override val isScrollInProgress: Boolean

    public override fun scrollBy(delta: Int): Int

    /** Requests [index] and [scrollOffset] as the next measured anchor. */
    public fun scrollToItem(index: Int, scrollOffset: Int = 0)

    /** Requests the logical start as the next measured position. */
    public fun requestScrollToStart()

    /** Requests the logical end as the next measured position. */
    public fun requestScrollToEnd()
}

/** Remembers one [LazyListState] across recompositions. */
@Composable
public expect fun rememberLazyListState(
    initialFirstVisibleItemIndex: Int = 0,
    initialFirstVisibleItemScrollOffset: Int = 0,
): LazyListState
