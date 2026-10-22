@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.unit.IntOffset
import com.jakewharton.mosaic.ui.unit.IntSize

/**
 * A stable handle that records the final surface bounds of one popup trigger.
 *
 * [isPlaced] is false before the trigger is placed and after it leaves composition.
 */
@Stable
public expect class TuiPopupAnchor internal constructor() {
    /** Whether this anchor currently belongs to a placed layout node. */
    public val isPlaced: Boolean
}

/** Creates a [TuiPopupAnchor] that remains stable for this composition location. */
@Composable
public expect fun rememberTuiPopupAnchor(): TuiPopupAnchor

/** The final terminal-cell bounds reported by a [TuiPopupAnchor]. */
@Immutable
public data class TuiPopupAnchorBounds(
    public val position: IntOffset,
    public val size: IntSize,
)

/** Marks a trigger whose final surface position can be used by [TuiPopup]. */
public expect fun Modifier.tuiPopupAnchor(anchor: TuiPopupAnchor): Modifier

/** Calculates a popup position from its trigger, host surface, and measured content. */
public fun interface TuiPopupPositionProvider {
    /** Maximum content size available around [anchorBounds] before positioning. */
    public fun calculateMaximumSize(
        anchorBounds: TuiPopupAnchorBounds,
        surfaceSize: IntSize,
    ): IntSize = surfaceSize

    public fun calculatePosition(
        anchorBounds: TuiPopupAnchorBounds,
        surfaceSize: IntSize,
        popupContentSize: IntSize,
    ): IntOffset

    public companion object {
        /** Places the popup above the trigger when possible, otherwise below it. */
        public val AboveStart: TuiPopupPositionProvider = createAboveStartPopupPositionProvider()

        /** Places a submenu beside the trigger, preferring its end side. */
        public val EndTop: TuiPopupPositionProvider = createEndTopPopupPositionProvider()
    }
}

internal expect fun createAboveStartPopupPositionProvider(): TuiPopupPositionProvider

internal expect fun createEndTopPopupPositionProvider(): TuiPopupPositionProvider

/**
 * A bounded terminal surface that can host [TuiPopup] children.
 *
 * An anchor and its popup must be declared inside the same host. Declare persistent content before
 * popup children. A popup is rendered and hit-tested above its preceding siblings without
 * contributing to their layout size.
 */
@Composable
public expect fun TuiPopupHost(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
): Unit

/**
 * Renders [content] over this [TuiPopupHost] using [anchor] and [positionProvider].
 *
 * A primary-pointer press not handled by [content] invokes [onDismissRequest]. The popup must be
 * declared after the host's persistent content so it occupies the top interaction layer.
 *
 * @param onDismissRequest `null` keeps unhandled pointer presses available to the host content.
 */
@Composable
public expect fun BoxScope.TuiPopup(
    anchor: TuiPopupAnchor,
    onDismissRequest: (() -> Unit)? = null,
    positionProvider: TuiPopupPositionProvider = TuiPopupPositionProvider.AboveStart,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
): Unit

/**
 * Renders centered, modal [content] over this [TuiPopupHost].
 *
 * The dialog traps keyboard focus within its content, consumes pointer events that are not handled
 * by its content, and restores the prior focus when removed. An unmodified Escape invokes
 * [onEscapeRequest] before focused content handles it. By default, this delegates to
 * [onDismissRequest]. A primary click outside [content] invokes [onDismissRequest] when
 * [dismissOnOutsideClick] is `true`.
 *
 * The dialog applies dim text style to the host cells behind it without replacing their characters,
 * then clears every character cell inside its measured bounds before applying caller drawing
 * modifiers and drawing [content]. Surface colors and other business styling remain the caller's
 * responsibility through [modifier].
 */
@Composable
public expect fun BoxScope.TuiDialog(
    onDismissRequest: () -> Unit,
    dismissOnOutsideClick: Boolean = true,
    onEscapeRequest: () -> Unit = onDismissRequest,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
): Unit

/** Receiver used to declare keyed content inside [TuiPopupMenu]. */
@Stable
public expect sealed interface TuiPopupMenuScope

/**
 * State owned by one popup menu level.
 *
 * Domain selection stays with the caller. This state retains only focus identity, viewport, and
 * nested-menu navigation across recompositions.
 *
 * @param initialFocusedKey `null` derives initial focus from the selected or first enabled item.
 * @property focusedKey `null` means the menu currently has no enabled item that can hold focus.
 */
@Stable
public expect class TuiPopupMenuState(
    initialFocusedKey: Any? = null,
) {
    public var focusedKey: Any?
        internal set
}

/**
 * Creates a [TuiPopupMenuState] that survives recomposition at this call site.
 *
 * @param initialFocusedKey `null` derives initial focus from the selected or first enabled item.
 */
@Composable
public expect fun rememberTuiPopupMenuState(initialFocusedKey: Any? = null): TuiPopupMenuState

/**
 * Declares one independently focusable menu command.
 *
 * @param leadingContent `null` omits the leading content slot.
 * @param trailingContent `null` omits the trailing content slot.
 * @param dismissOnClick When true, dismisses the menu group before invoking [onClick].
 * False delegates leaf dismissal to the caller, for exact-handle admission that must precede
 * disposal. Escape/outside dismissal is unaffected; a caller accepting the command must close
 * its own menu. Submenu navigation never dismisses the group.
 */
@Suppress("FunctionName")
public expect fun TuiPopupMenuScope.TuiPopupMenuItem(
    key: Any,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    dismissOnClick: Boolean = true,
    content: @Composable () -> Unit,
): Unit

/**
 * Declares a non-focusable separator.
 *
 * @param key `null` generates a composition-local key from the divider's declaration position.
 */
@Suppress("FunctionName")
public expect fun TuiPopupMenuScope.TuiPopupMenuDivider(
    key: Any? = null,
    modifier: Modifier = Modifier,
): Unit

/**
 * Declares a command that opens a child menu beside this item.
 *
 * @param initialSubmenuFocusedKey `null` lets the child derive focus from its selected or first
 * enabled item.
 * @param leadingContent `null` omits the leading content slot.
 * @param trailingContent `null` renders the default child-menu indicator.
 */
@Suppress("FunctionName")
public expect fun TuiPopupMenuScope.TuiPopupSubmenuItem(
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
): Unit

/**
 * Displays a keyed popup menu over the surrounding [TuiPopupHost].
 *
 * The menu owns focus navigation and popup lifecycle but not domain selection. Selecting a normal
 * item dismisses the whole menu group before invoking that item's callback unless the item
 * explicitly delegates dismissal to its caller with `dismissOnClick = false`.
 */
@Composable
public expect fun BoxScope.TuiPopupMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    anchor: TuiPopupAnchor,
    state: TuiPopupMenuState = rememberTuiPopupMenuState(),
    positionProvider: TuiPopupPositionProvider = TuiPopupPositionProvider.AboveStart,
    backgroundColor: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
    content: TuiPopupMenuScope.() -> Unit,
): Unit

/**
 * Displays a context menu at the secondary activation position over the surrounding
 * [TuiPopupHost].
 *
 * [clickPosition] is local to [anchor]. Pointer activation starts the menu at that terminal cell,
 * while `null` starts it at the anchor's beginning for keyboard activation. The final position is
 * clamped to the popup host.
 */
@Composable
public expect fun BoxScope.TuiContextMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    anchor: TuiPopupAnchor,
    clickPosition: IntOffset? = null,
    state: TuiPopupMenuState = rememberTuiPopupMenuState(),
    backgroundColor: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
    content: TuiPopupMenuScope.() -> Unit,
): Unit
