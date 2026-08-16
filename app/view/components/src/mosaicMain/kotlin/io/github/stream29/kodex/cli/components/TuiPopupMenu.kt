package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.focus.FocusState
import com.jakewharton.mosaic.focus.focusTrap
import com.jakewharton.mosaic.focus.onFocusChanged
import com.jakewharton.mosaic.layout.ContentDrawScope
import com.jakewharton.mosaic.layout.DrawModifier
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.layout.MeasurePolicy
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.onPointerEvent
import com.jakewharton.mosaic.layout.onPreviewKeyEvent
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Filler
import com.jakewharton.mosaic.ui.Layout
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Spacer
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.isSpecifiedColor
import com.jakewharton.mosaic.ui.unit.Constraints

/** Receiver used to declare keyed content inside [TuiPopupMenu]. */
@Stable
public sealed interface TuiPopupMenuScope

/**
 * State owned by one popup menu level.
 *
 * Domain selection stays with the caller. This state retains only focus identity, viewport, and
 * nested-menu navigation across recompositions.
 *
 * @property initialFocusedKey `null` derives initial focus from the selected or first enabled item.
 * @property focusedKey `null` means the menu currently has no enabled item that can hold focus.
 * @property openSubmenuKey `null` means this menu level has no open child menu.
 * @property requestedFirstVisibleKey `null` means no explicit viewport scroll is pending.
 * @property pendingFocusRequest `null` means no offscreen item is waiting to receive focus.
 */
@Stable
public class TuiPopupMenuState(
    private val initialFocusedKey: Any? = null,
) {
    public var focusedKey: Any? by mutableStateOf(initialFocusedKey)
        internal set

    internal var openSubmenuKey: Any? by mutableStateOf(null)
    internal var viewport: TuiPopupMenuViewport by mutableStateOf(TuiPopupMenuViewport.Uninitialized)
    internal var requestedFirstVisibleKey: Any? by mutableStateOf(null)
    internal var pendingFocusRequest: TuiPopupMenuFocusRequest? by mutableStateOf(null)

    private val submenuAnchors = mutableMapOf<Any, TuiPopupAnchor>()
    private val submenuStates = mutableMapOf<Any, TuiPopupMenuState>()
    private val focusRequesters = mutableMapOf<Any, FocusRequester>()
    private var previousEnabledKeys: List<Any> = emptyList()
    private var focusRequestId: Long = 0

    /** @return `null` when [entries] contains no enabled item. */
    internal fun resolveFocusedKey(entries: List<TuiPopupMenuEntry>): Any? {
        val enabledItems = entries.filterIsInstance<TuiPopupMenuEntry.Item>().filter { it.enabled }
        return focusedKey.takeIf { key -> enabledItems.any { it.key == key } }
            ?: previousEnabledKeys.indexOf(focusedKey).takeIf { it >= 0 }?.let { previousIndex ->
                enabledItems.getOrNull(previousIndex.coerceAtMost(enabledItems.lastIndex))?.key
            }
            ?: initialFocusedKey.takeIf { key -> enabledItems.any { it.key == key } }
            ?: enabledItems.firstOrNull(TuiPopupMenuEntry.Item::selected)?.key
            ?: enabledItems.firstOrNull()?.key
    }

    /** @param resolvedFocusedKey `null` means [entries] contains no enabled item. */
    internal fun reconcile(entries: List<TuiPopupMenuEntry>, resolvedFocusedKey: Any?) {
        val keys = entries.mapTo(mutableSetOf(), TuiPopupMenuEntry::key)
        focusedKey = resolvedFocusedKey
        if (openSubmenuKey !in keys) openSubmenuKey = null
        if (viewport.firstVisibleKey !in keys || viewport.lastVisibleKey !in keys) {
            viewport = TuiPopupMenuViewport.Uninitialized
        }
        if (requestedFirstVisibleKey !in keys) requestedFirstVisibleKey = null
        submenuAnchors.keys.retainAll(keys)
        submenuStates.keys.retainAll(keys)
        focusRequesters.keys.retainAll(keys)
        previousEnabledKeys = entries
            .filterIsInstance<TuiPopupMenuEntry.Item>()
            .filter(TuiPopupMenuEntry.Item::enabled)
            .map(TuiPopupMenuEntry.Item::key)
    }

    internal fun moveFocus(entries: List<TuiPopupMenuEntry>, direction: TuiPopupMenuFocusDirection) {
        val enabledItems = entries.filterIsInstance<TuiPopupMenuEntry.Item>().filter { it.enabled }
        if (enabledItems.isEmpty()) return
        val currentIndex = enabledItems.indexOfFirst { it.key == focusedKey }
        val target = when (direction) {
            TuiPopupMenuFocusDirection.Previous -> enabledItems[
                if (currentIndex <= 0) enabledItems.lastIndex else currentIndex - 1
            ]

            TuiPopupMenuFocusDirection.Next -> enabledItems[
                if (currentIndex !in enabledItems.indices || currentIndex == enabledItems.lastIndex) {
                    0
                } else {
                    currentIndex + 1
                }
            ]

            TuiPopupMenuFocusDirection.First -> enabledItems.first()
            TuiPopupMenuFocusDirection.Last -> enabledItems.last()
        }
        focusedKey = target.key
        openSubmenuKey = null
        requestFocus(target.key)
    }

    internal fun scroll(entries: List<TuiPopupMenuEntry>, delta: Int) {
        if (entries.isEmpty() || delta == 0) return
        val currentIndex = entries.indexOfFirst { it.key == viewport.firstVisibleKey }
            .takeIf { it >= 0 }
            ?: 0
        val targetIndex = (currentIndex + delta).coerceIn(0, entries.lastIndex)
        if (targetIndex == currentIndex) return
        requestedFirstVisibleKey = entries[targetIndex].key
        val targetItem = if (delta > 0) {
            entries.drop(targetIndex).filterIsInstance<TuiPopupMenuEntry.Item>().firstOrNull { it.enabled }
        } else {
            entries.take(targetIndex + 1).filterIsInstance<TuiPopupMenuEntry.Item>().lastOrNull { it.enabled }
        }
        if (targetItem != null) {
            focusedKey = targetItem.key
            openSubmenuKey = null
            requestFocus(targetItem.key)
        }
    }

    internal fun requestFocus(key: Any) {
        val request = TuiPopupMenuFocusRequest(++focusRequestId, key)
        pendingFocusRequest = if (focusRequesters[key]?.requestFocus() == true) null else request
    }

    internal fun focusSettled(request: TuiPopupMenuFocusRequest) {
        if (pendingFocusRequest == request) pendingFocusRequest = null
    }

    internal fun submenuAnchor(key: Any): TuiPopupAnchor =
        submenuAnchors.getOrPut(key) { TuiPopupAnchor() }

    /** @param initialFocusedKey `null` lets the child derive focus from its declared items. */
    internal fun submenuState(key: Any, initialFocusedKey: Any?): TuiPopupMenuState =
        submenuStates.getOrPut(key) { TuiPopupMenuState(initialFocusedKey) }

    internal fun focusRequester(key: Any): FocusRequester =
        focusRequesters.getOrPut(key) { FocusRequester() }
}

/**
 * Creates a [TuiPopupMenuState] that survives recomposition at this call site.
 *
 * @param initialFocusedKey `null` derives initial focus from the selected or first enabled item.
 */
@Composable
public fun rememberTuiPopupMenuState(initialFocusedKey: Any? = null): TuiPopupMenuState =
    remember { TuiPopupMenuState(initialFocusedKey) }

/**
 * Declares one independently focusable menu command.
 *
 * @param leadingContent `null` omits the leading content slot.
 * @param trailingContent `null` omits the trailing content slot.
 */
@Suppress("FunctionName")
public fun TuiPopupMenuScope.TuiPopupMenuItem(
    key: Any,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    entries += TuiPopupMenuEntry.Item(
        key = key,
        modifier = modifier,
        enabled = enabled,
        selected = selected,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        content = content,
        onClick = onClick,
        submenu = null,
    )
}

/**
 * Declares a non-focusable separator.
 *
 * @param key `null` generates a composition-local key from the divider's declaration position.
 */
@Suppress("FunctionName")
public fun TuiPopupMenuScope.TuiPopupMenuDivider(
    key: Any? = null,
    modifier: Modifier = Modifier,
) {
    val entries = entries
    entries += TuiPopupMenuEntry.Divider(
        key = key ?: TuiPopupMenuGeneratedKey(entries.size),
        modifier = modifier,
    )
}

/**
 * Declares a command that opens a child menu beside this item.
 *
 * @param initialSubmenuFocusedKey `null` lets the child derive focus from its selected or first
 * enabled item.
 * @param leadingContent `null` omits the leading content slot.
 * @param trailingContent `null` renders the default child-menu indicator.
 */
@Suppress("FunctionName")
public fun TuiPopupMenuScope.TuiPopupSubmenuItem(
    key: Any,
    submenuContent: TuiPopupMenuScope.() -> Unit,
    initialSubmenuFocusedKey: Any? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    positionProvider: TuiPopupPositionProvider = TuiPopupPositionProvider.EndTop,
    backgroundColor: Color = Color.Unspecified,
    submenuModifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    entries += TuiPopupMenuEntry.Item(
        key = key,
        modifier = modifier,
        enabled = enabled,
        selected = selected,
        leadingContent = leadingContent,
        trailingContent = trailingContent ?: { Text(">") },
        content = content,
        onClick = {},
        submenu = TuiPopupSubmenu(
            initialFocusedKey = initialSubmenuFocusedKey,
            positionProvider = positionProvider,
            backgroundColor = backgroundColor,
            modifier = submenuModifier,
            content = submenuContent,
        ),
    )
}

/**
 * Displays a keyed popup menu over the surrounding [TuiPopupHost].
 *
 * The menu owns focus navigation and popup lifecycle but not domain selection. Selecting a normal
 * item dismisses the whole menu group before invoking that item's callback.
 */
@Composable
public fun BoxScope.TuiPopupMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    anchor: TuiPopupAnchor,
    state: TuiPopupMenuState = rememberTuiPopupMenuState(),
    positionProvider: TuiPopupPositionProvider = TuiPopupPositionProvider.AboveStart,
    backgroundColor: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
    content: TuiPopupMenuScope.() -> Unit,
) {
    if (!expanded) return
    val entries = TuiPopupMenuScopeImpl().apply(content).entries.toList()
    requireUniqueKeys(entries)
    if (entries.isEmpty()) return

    TuiPopupMenuLevel(
        anchor = anchor,
        state = state,
        entries = entries,
        positionProvider = positionProvider,
        backgroundColor = backgroundColor,
        modifier = modifier,
        dismissGroup = onDismissRequest,
        navigateBack = null,
    )
}

/** @param navigateBack `null` identifies the root menu level, which dismisses the whole group. */
@Composable
private fun BoxScope.TuiPopupMenuLevel(
    anchor: TuiPopupAnchor,
    state: TuiPopupMenuState,
    entries: List<TuiPopupMenuEntry>,
    positionProvider: TuiPopupPositionProvider,
    backgroundColor: Color,
    modifier: Modifier,
    dismissGroup: () -> Unit,
    navigateBack: (() -> Unit)?,
) {
    val focusedKey = state.resolveFocusedKey(entries)
    state.reconcile(entries, focusedKey)
    val latestDismissGroup = rememberUpdatedState(dismissGroup)
    val latestNavigateBack = rememberUpdatedState(navigateBack)

    TuiPopup(
        anchor = anchor,
        onDismissRequest = if (navigateBack == null) {
            { latestDismissGroup.value() }
        } else {
            null
        },
        positionProvider = positionProvider,
    ) {
        TuiPopupMenuSurface(
            entries = entries,
            focusedKey = focusedKey,
            state = state,
            backgroundColor = backgroundColor,
            modifier = modifier,
            dismiss = {
                latestNavigateBack.value?.invoke() ?: latestDismissGroup.value()
            },
            dismissGroup = { latestDismissGroup.value() },
            navigateBack = latestNavigateBack.value,
        )
    }

    val openItem = entries
        .filterIsInstance<TuiPopupMenuEntry.Item>()
        .firstOrNull { item ->
            item.enabled && item.key == state.openSubmenuKey && item.submenu != null
        }
    val submenu = openItem?.submenu
    if (openItem != null && submenu != null) {
        val parentKey = openItem.key
        val childScope = TuiPopupMenuScopeImpl().apply(submenu.content)
        val childEntries = childScope.entries.toList()
        requireUniqueKeys(childEntries)
        if (childEntries.isNotEmpty()) {
            TuiPopupMenuLevel(
                anchor = state.submenuAnchor(parentKey),
                state = state.submenuState(parentKey, submenu.initialFocusedKey),
                entries = childEntries,
                positionProvider = submenu.positionProvider,
                backgroundColor = submenu.backgroundColor,
                modifier = submenu.modifier,
                dismissGroup = { latestDismissGroup.value() },
                navigateBack = {
                    state.openSubmenuKey = null
                    state.requestFocus(parentKey)
                },
            )
        }
    }
}

/**
 * @param focusedKey `null` means this level has no enabled item.
 * @param navigateBack `null` identifies the root menu level.
 */
@Composable
private fun TuiPopupMenuSurface(
    entries: List<TuiPopupMenuEntry>,
    focusedKey: Any?,
    state: TuiPopupMenuState,
    backgroundColor: Color,
    modifier: Modifier,
    dismiss: () -> Unit,
    dismissGroup: () -> Unit,
    navigateBack: (() -> Unit)?,
) {
    val latestDismiss = rememberUpdatedState(dismiss)
    val dismissOnEscapeModifier = Modifier.onPreviewKeyEvent { event ->
        if (event != Escape) return@onPreviewKeyEvent false
        latestDismiss.value()
        true
    }
    val wheelModifier = Modifier.onPointerEvent { event ->
        if (event.type != MouseEvent.Type.Press) return@onPointerEvent false
        when (event.button) {
            MouseEvent.Button.WheelUp -> state.scroll(entries, delta = -1)
            MouseEvent.Button.WheelDown -> state.scroll(entries, delta = 1)
            else -> return@onPointerEvent false
        }
        true
    }

    Box(
        modifier = dismissOnEscapeModifier
            .then(modifier)
            .focusTrap()
            .then(wheelModifier),
    ) {
        if (backgroundColor.isSpecifiedColor) {
            Filler(
                char = ' ',
                modifier = Modifier.matchParentSize(),
                background = backgroundColor,
                textStyle = TextStyle.Empty,
            )
        }
        TuiPopupMenuLayout(
            entries = entries,
            focusedKey = focusedKey,
            state = state,
            dismissGroup = dismissGroup,
            navigateBack = navigateBack,
        )
    }
}

/**
 * @param focusedKey `null` means this level has no enabled item.
 * @param navigateBack `null` identifies the root menu level.
 */
@Composable
private fun TuiPopupMenuLayout(
    entries: List<TuiPopupMenuEntry>,
    focusedKey: Any?,
    state: TuiPopupMenuState,
    dismissGroup: () -> Unit,
    navigateBack: (() -> Unit)?,
) {
    val measurePolicy = remember(entries.map(TuiPopupMenuEntry::key), focusedKey, state) {
        MeasurePolicy { measurables, constraints ->
            check(constraints.hasBoundedWidth && constraints.hasBoundedHeight) {
                "TuiPopupMenu must be measured with bounded constraints."
            }
            if (constraints.maxWidth == 0 || constraints.maxHeight == 0) {
                state.viewport = TuiPopupMenuViewport.Empty
                return@MeasurePolicy layout(0, 0) {}
            }

            val width = measurables
                .maxOfOrNull { measurable -> measurable.maxIntrinsicWidth(constraints.maxHeight) }
                ?.coerceIn(1, constraints.maxWidth)
                ?: 0
            val itemConstraints = Constraints(
                minWidth = width,
                maxWidth = width,
                minHeight = 0,
                maxHeight = constraints.maxHeight,
            )
            val placeables = measurables.map { measurable -> measurable.measure(itemConstraints) }
            val topIndicator = placeables[0]
            val bottomIndicator = placeables[1]
            val itemPlaceables = placeables.drop(2)
            val requestedFirstIndex = entries.indexOfFirst {
                it.key == state.requestedFirstVisibleKey
            }.takeIf { it >= 0 }
            val previousFirstIndex = entries.indexOfFirst {
                it.key == state.viewport.firstVisibleKey
            }.takeIf { it >= 0 }
            val focusedIndex = entries.indexOfFirst { it.key == focusedKey }
            val honorRequestedViewport = requestedFirstIndex != null
            var firstIndex = requestedFirstIndex ?: previousFirstIndex ?: 0
            var viewport = calculatePopupMenuViewport(
                firstIndex = firstIndex,
                itemHeights = itemPlaceables.map { it.height },
                maximumHeight = constraints.maxHeight,
                indicatorHeight = maxOf(topIndicator.height, bottomIndicator.height),
            )
            if (!honorRequestedViewport && focusedIndex >= 0) {
                if (focusedIndex < viewport.firstIndex) {
                    firstIndex = focusedIndex
                    viewport = calculatePopupMenuViewport(
                        firstIndex = firstIndex,
                        itemHeights = itemPlaceables.map { it.height },
                        maximumHeight = constraints.maxHeight,
                        indicatorHeight = maxOf(topIndicator.height, bottomIndicator.height),
                    )
                } else {
                    while (focusedIndex > viewport.lastIndex && firstIndex < focusedIndex) {
                        firstIndex++
                        viewport = calculatePopupMenuViewport(
                            firstIndex = firstIndex,
                            itemHeights = itemPlaceables.map { it.height },
                            maximumHeight = constraints.maxHeight,
                            indicatorHeight = maxOf(topIndicator.height, bottomIndicator.height),
                        )
                    }
                }
            }

            state.requestedFirstVisibleKey = null
            state.viewport = viewport.toState(entries)
            layout(width, viewport.height) {
                var y = 0
                if (viewport.showTopIndicator) {
                    topIndicator.place(0, y)
                    y += topIndicator.height
                }
                if (!viewport.isEmpty) {
                    for (index in viewport.firstIndex..viewport.lastIndex) {
                        itemPlaceables[index].place(0, y)
                        y += itemPlaceables[index].height
                    }
                }
                if (viewport.showBottomIndicator) {
                    bottomIndicator.place(0, y)
                }
            }
        }
    }

    Layout(
        content = {
            TuiPopupMenuOverflowIndicator("^")
            TuiPopupMenuOverflowIndicator("v")
            entries.forEachIndexed { index, entry ->
                key(entry.key) {
                    when (entry) {
                        is TuiPopupMenuEntry.Divider -> TuiPopupMenuDividerContent(entry)
                        is TuiPopupMenuEntry.Item -> TuiPopupMenuItemContent(
                            entry = entry,
                            entries = entries,
                            visible = index in state.viewport.visibleIndices,
                            focused = entry.key == focusedKey,
                            state = state,
                            dismissGroup = dismissGroup,
                            navigateBack = navigateBack,
                        )
                    }
                }
            }
        },
        debugInfo = { "TuiPopupMenuLayout(entries=${entries.size})" },
        measurePolicy = measurePolicy,
    )
}

/** @param navigateBack `null` identifies an item in the root menu level. */
@Composable
private fun TuiPopupMenuItemContent(
    entry: TuiPopupMenuEntry.Item,
    entries: List<TuiPopupMenuEntry>,
    visible: Boolean,
    focused: Boolean,
    state: TuiPopupMenuState,
    dismissGroup: () -> Unit,
    navigateBack: (() -> Unit)?,
) {
    val focusRequester = state.focusRequester(entry.key)
    val focusRequest = state.pendingFocusRequest
    if (visible && focusRequest?.key == entry.key) {
        LaunchedEffect(focusRequest.id, entry.key) {
            withFrameNanos { }
            if (focusRequester.requestFocus()) state.focusSettled(focusRequest)
        }
    }
    val submenuAnchor = entry.submenu?.let { state.submenuAnchor(entry.key) }
    val anchorModifier = if (submenuAnchor == null) {
        Modifier
    } else {
        Modifier.tuiPopupAnchor(submenuAnchor)
    }
    val enabled = visible && entry.enabled

    TuiPressable(
        onClick = {
            if (entry.submenu == null) {
                dismissGroup()
                entry.onClick()
            } else {
                state.focusedKey = entry.key
                state.openSubmenuKey = entry.key
            }
        },
        enabled = enabled,
        focusRequester = focusRequester,
        autoFocus = visible && focused,
        modifier = entry.modifier
            .then(anchorModifier)
            .onFocusChanged { focusState ->
                val pendingKey = state.pendingFocusRequest?.key
                if (
                    focusState == FocusState.Active &&
                    (pendingKey == null || pendingKey == entry.key)
                ) {
                    state.focusedKey = entry.key
                }
            },
        onKeyEvent = { event ->
            when {
                (event == Enter || event == Space) && entry.key != state.focusedKey -> true

                event == Tab -> {
                    state.moveFocus(entries, TuiPopupMenuFocusDirection.Next)
                    true
                }

                event == ShiftTab -> {
                    state.moveFocus(entries, TuiPopupMenuFocusDirection.Previous)
                    true
                }

                event.hasNoModifiers() && event.key == "ArrowUp" -> {
                    state.moveFocus(entries, TuiPopupMenuFocusDirection.Previous)
                    true
                }

                event.hasNoModifiers() && event.key == "ArrowDown" -> {
                    state.moveFocus(entries, TuiPopupMenuFocusDirection.Next)
                    true
                }

                event.hasNoModifiers() && event.key == "Home" -> {
                    state.moveFocus(entries, TuiPopupMenuFocusDirection.First)
                    true
                }

                event.hasNoModifiers() && event.key == "End" -> {
                    state.moveFocus(entries, TuiPopupMenuFocusDirection.Last)
                    true
                }

                event.hasNoModifiers() && event.key == "ArrowRight" && entry.submenu != null -> {
                    state.openSubmenuKey = entry.key
                    true
                }

                event.hasNoModifiers() && event.key == "ArrowLeft" && navigateBack != null -> {
                    navigateBack()
                    true
                }

                else -> false
            }
        },
    ) { _, hovered, pressed ->
        val textStyle = tuiInteractionTextStyle(
            enabled = entry.enabled,
            hovered = hovered,
            pressed = pressed,
            selected = entry.selected,
            idleTextStyle = TuiTheme.typography.label,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(TuiPopupMenuItemStyleModifier(textStyle)),
        ) {
            Text("[")
            entry.leadingContent?.let { leading ->
                leading()
                Text(" ")
            }
            entry.content()
            Filler(
                char = ' ',
                modifier = Modifier
                    .height(1)
                    .weight(1f),
                textStyle = TextStyle.Empty,
            )
            if (entry.trailingContent != null) {
                Text(" ")
                entry.trailingContent.invoke()
            }
            Text("]")
        }
    }
}

@Composable
private fun TuiPopupMenuDividerContent(entry: TuiPopupMenuEntry.Divider) {
    Filler(
        char = '-',
        modifier = entry.modifier
            .fillMaxWidth()
            .height(1),
        textStyle = TuiTheme.typography.supporting,
    )
}

@Composable
private fun TuiPopupMenuOverflowIndicator(value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text("[")
        Spacer(Modifier.weight(1f))
        Text(value, textStyle = TuiTheme.typography.supporting)
        Spacer(Modifier.weight(1f))
        Text("]")
    }
}

private fun calculatePopupMenuViewport(
    firstIndex: Int,
    itemHeights: List<Int>,
    maximumHeight: Int,
    indicatorHeight: Int,
): TuiPopupMenuMeasuredViewport {
    if (itemHeights.isEmpty() || maximumHeight <= 0) return TuiPopupMenuMeasuredViewport.Empty
    val normalizedFirst = firstIndex.coerceIn(0, itemHeights.lastIndex)
    var showTop = normalizedFirst > 0 && indicatorHeight < maximumHeight
    var usedHeight = if (showTop) indicatorHeight else 0
    var lastIndex = normalizedFirst - 1
    while (lastIndex < itemHeights.lastIndex) {
        val nextIndex = lastIndex + 1
        val nextHeight = itemHeights[nextIndex]
        if (lastIndex >= normalizedFirst && usedHeight + nextHeight > maximumHeight) break
        if (lastIndex < normalizedFirst && usedHeight + nextHeight > maximumHeight) {
            showTop = false
            usedHeight = 0
        }
        if (usedHeight + nextHeight > maximumHeight) break
        usedHeight += nextHeight
        lastIndex = nextIndex
    }
    if (lastIndex < normalizedFirst) {
        return TuiPopupMenuMeasuredViewport.Empty
    }

    var showBottom = lastIndex < itemHeights.lastIndex && indicatorHeight < maximumHeight
    while (
        showBottom &&
        usedHeight + indicatorHeight > maximumHeight &&
        lastIndex > normalizedFirst
    ) {
        usedHeight -= itemHeights[lastIndex]
        lastIndex--
    }
    if (showBottom && usedHeight + indicatorHeight > maximumHeight) showBottom = false
    val height = usedHeight + if (showBottom) indicatorHeight else 0
    return TuiPopupMenuMeasuredViewport(
        firstIndex = normalizedFirst,
        lastIndex = lastIndex,
        showTopIndicator = showTop,
        showBottomIndicator = showBottom,
        height = height,
    )
}

private fun TuiPopupMenuMeasuredViewport.toState(
    entries: List<TuiPopupMenuEntry>,
): TuiPopupMenuViewport = if (isEmpty) {
    TuiPopupMenuViewport.Empty
} else {
    TuiPopupMenuViewport(
        firstVisibleKey = entries[firstIndex].key,
        lastVisibleKey = entries[lastIndex].key,
        visibleIndices = firstIndex..lastIndex,
        canScrollBackward = showTopIndicator,
        canScrollForward = showBottomIndicator,
    )
}

private fun requireUniqueKeys(entries: List<TuiPopupMenuEntry>) {
    val duplicate = entries.groupingBy(TuiPopupMenuEntry::key).eachCount()
        .entries
        .firstOrNull { it.value > 1 }
    require(duplicate == null) { "Popup menu contains duplicate key ${duplicate?.key}." }
}

private val TuiPopupMenuScope.entries: MutableList<TuiPopupMenuEntry>
    get() = (this as TuiPopupMenuScopeImpl).entries

private class TuiPopupMenuScopeImpl : TuiPopupMenuScope {
    val entries: MutableList<TuiPopupMenuEntry> = mutableListOf()
}

internal sealed interface TuiPopupMenuEntry {
    val key: Any
    val modifier: Modifier

    /**
     * @property leadingContent `null` means the item has no leading content.
     * @property trailingContent `null` means the item has no trailing content.
     * @property submenu `null` identifies an actionable leaf item.
     */
    class Item(
        override val key: Any,
        override val modifier: Modifier,
        val enabled: Boolean,
        val selected: Boolean,
        val leadingContent: (@Composable () -> Unit)?,
        val trailingContent: (@Composable () -> Unit)?,
        val content: @Composable () -> Unit,
        val onClick: () -> Unit,
        val submenu: TuiPopupSubmenu?,
    ) : TuiPopupMenuEntry

    class Divider(
        override val key: Any,
        override val modifier: Modifier,
    ) : TuiPopupMenuEntry
}

/** @property initialFocusedKey `null` lets the child derive focus from its declared items. */
internal class TuiPopupSubmenu(
    val initialFocusedKey: Any?,
    val positionProvider: TuiPopupPositionProvider,
    val backgroundColor: Color,
    val modifier: Modifier,
    val content: TuiPopupMenuScope.() -> Unit,
)

private data class TuiPopupMenuGeneratedKey(val index: Int)

internal data class TuiPopupMenuFocusRequest(
    val id: Long,
    val key: Any,
)

/**
 * @property firstVisibleKey `null` means the viewport has not been measured or is empty.
 * @property lastVisibleKey `null` means the viewport has not been measured or is empty.
 */
internal data class TuiPopupMenuViewport(
    val firstVisibleKey: Any?,
    val lastVisibleKey: Any?,
    val visibleIndices: IntRange,
    val canScrollBackward: Boolean,
    val canScrollForward: Boolean,
) {
    companion object {
        val Uninitialized = TuiPopupMenuViewport(
            firstVisibleKey = null,
            lastVisibleKey = null,
            visibleIndices = 0..Int.MAX_VALUE,
            canScrollBackward = false,
            canScrollForward = false,
        )
        val Empty = TuiPopupMenuViewport(
            firstVisibleKey = null,
            lastVisibleKey = null,
            visibleIndices = IntRange.EMPTY,
            canScrollBackward = false,
            canScrollForward = false,
        )
    }
}

private data class TuiPopupMenuMeasuredViewport(
    val firstIndex: Int,
    val lastIndex: Int,
    val showTopIndicator: Boolean,
    val showBottomIndicator: Boolean,
    val height: Int,
) {
    val isEmpty: Boolean get() = firstIndex > lastIndex

    companion object {
        val Empty = TuiPopupMenuMeasuredViewport(
            firstIndex = 0,
            lastIndex = -1,
            showTopIndicator = false,
            showBottomIndicator = false,
            height = 0,
        )
    }
}

internal enum class TuiPopupMenuFocusDirection {
    Previous,
    Next,
    First,
    Last,
}

private class TuiPopupMenuItemStyleModifier(
    private val textStyle: TextStyle,
) : DrawModifier {
    override fun ContentDrawScope.draw() {
        drawContent()
        if (textStyle != TextStyle.Unspecified) drawRect(textStyle = textStyle)
    }

    override fun toString(): String = "TuiPopupMenuItemStyle($textStyle)"
}

private fun KeyEvent.hasNoModifiers(): Boolean = !alt && !ctrl && !shift

private val Tab: KeyEvent = KeyEvent("Tab")
private val ShiftTab: KeyEvent = KeyEvent("Tab", shift = true)
private val Enter: KeyEvent = KeyEvent("Enter")
private val Space: KeyEvent = KeyEvent(" ")
private val Escape: KeyEvent = KeyEvent("Escape")
