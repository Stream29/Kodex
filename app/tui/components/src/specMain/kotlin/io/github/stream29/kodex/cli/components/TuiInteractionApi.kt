@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.text.AnnotatedString
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.RowScope
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.unit.IntOffset

/** Controls whether interaction feedback may exchange semantic foreground and container colors. */
public enum class TuiInteractionStyle {
    /** Uses terminal inverse video for pressed and selected states. */
    TerminalInvert,

    /** Preserves caller-provided colors and expresses interaction through text emphasis. */
    PreserveColors,
}

/**
 * Resolves the shared terminal interaction style without changing focus presentation.
 *
 * Focus remains represented by the physical terminal cursor. Hover adds bold, press combines bold
 * with inverse video, selection uses inverse video, and disabled content is dim.
 */
public expect fun tuiInteractionTextStyle(
    enabled: Boolean = true,
    hovered: Boolean = false,
    pressed: Boolean = false,
    selected: Boolean = false,
    idleTextStyle: TextStyle = TextStyle.Unspecified,
    interactionStyle: TuiInteractionStyle = TuiInteractionStyle.TerminalInvert,
): TextStyle

/**
 * A focusable action surface with shared Enter, Space, and primary-pointer activation semantics.
 *
 * [content] receives the current focus, hover, and press state so it can render an appropriate
 * treatment. Focus selection, pointer focus, traversal, and cursor ownership are provided by the
 * Mosaic runtime. An optional secondary action is available from the secondary pointer button or
 * Shift+F10 or the Menu/Application key without changing focus. Its position is local to this
 * surface for pointer activation and `null` for keyboard activation.
 */
@Composable
public expect fun TuiPressable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onKeyEvent: ((KeyEvent) -> Boolean)? = null,
    autoFocus: Boolean = false,
    onSecondaryClick: ((IntOffset?) -> Unit)? = null,
    content: @Composable (isFocused: Boolean, isHovered: Boolean, isPressed: Boolean) -> Unit,
): Unit

/**
 * Compact bracketed command surface with terminal-native interaction feedback.
 *
 * Focus is represented by the terminal cursor, hover uses bold text, a held primary-pointer press
 * combines inverse video and bold by default, [selected] uses inverse video, and a disabled button
 * is dim. [interactionStyle] can preserve semantic foreground/container pairs instead. Enter,
 * Space, and primary-pointer activation converge on the same command. [idleTextStyle] applies while
 * the enabled button is neither selected, hovered, nor pressed. [onSecondaryClick] optionally
 * handles the secondary pointer button, Shift+F10, and the Menu/Application key. Its position is
 * local to this button for pointer activation and `null` for keyboard activation.
 */
@Composable
public expect fun TuiButton(
    label: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    idleTextStyle: TextStyle = TuiTheme.typography.label,
    selected: Boolean = false,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onKeyEvent: ((KeyEvent) -> Boolean)? = null,
    autoFocus: Boolean = false,
    onSecondaryClick: ((IntOffset?) -> Unit)? = null,
    interactionStyle: TuiInteractionStyle = TuiInteractionStyle.TerminalInvert,
    onClick: () -> Unit,
): Unit

/** [TuiButton] overload retaining spans in a structured label. */
@Composable
public expect fun TuiButton(
    label: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    idleTextStyle: TextStyle = TuiTheme.typography.label,
    selected: Boolean = false,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onKeyEvent: ((KeyEvent) -> Boolean)? = null,
    autoFocus: Boolean = false,
    onSecondaryClick: ((IntOffset?) -> Unit)? = null,
    interactionStyle: TuiInteractionStyle = TuiInteractionStyle.TerminalInvert,
    onClick: () -> Unit,
): Unit

/**
 * A terminal checkbox whose marker and label form one pressable setting row.
 *
 * Enter, Space, and primary-pointer activation toggle the value. Focus remains represented by the
 * terminal cursor; hover, press, and disabled states use the shared interaction treatment.
 */
@Composable
public expect fun TuiCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    idleTextStyle: TextStyle = TuiTheme.typography.body,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onKeyEvent: ((KeyEvent) -> Boolean)? = null,
    autoFocus: Boolean = false,
    onSecondaryClick: ((IntOffset?) -> Unit)? = null,
    interactionStyle: TuiInteractionStyle = TuiInteractionStyle.TerminalInvert,
): Unit

/** A trailing-aligned dialog action row with one terminal cell between actions. */
@Composable
public expect fun TuiDialogActionRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
): Unit

/**
 * State shared by a [TuiDropdownTrigger] and its host-level [TuiDropdownMenu].
 *
 * Keep the menu as a direct child of the surrounding [TuiPopupHost] so it can render above
 * dialogs and other persistent content without inheriting the trigger's layout constraints.
 */
@Stable
public expect class TuiDropdownState internal constructor() {
    /** Whether the associated menu is currently displayed. */
    public var expanded: Boolean
        private set

    /** Opens the associated menu. */
    public fun expand()

    /** Closes the associated menu. */
    public fun dismiss()
}

/** Creates [TuiDropdownState] that survives recomposition at this call site. */
@Composable
public expect fun rememberTuiDropdownState(): TuiDropdownState

/**
 * Renders a popup-menu trigger for [dropdownState].
 *
 * Removing or disabling the trigger dismisses its menu so a stale menu cannot survive a route or
 * configuration change.
 */
@Composable
public expect fun TuiDropdownTrigger(
    dropdownState: TuiDropdownState,
    label: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    enabled: Boolean = true,
    autoFocus: Boolean = false,
    interactionStyle: TuiInteractionStyle = TuiInteractionStyle.TerminalInvert,
): Unit

/**
 * Displays a menu for [dropdownState] over the surrounding [TuiPopupHost].
 *
 * Call this after persistent content, including the matching [TuiDropdownTrigger].
 */
@Composable
public expect fun BoxScope.TuiDropdownMenu(
    dropdownState: TuiDropdownState,
    positionProvider: TuiPopupPositionProvider = TuiPopupPositionProvider.AboveStart,
    backgroundColor: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
    content: TuiPopupMenuScope.() -> Unit,
): Unit

/** Displays one selectable menu item for each [options] value. */
@Composable
public expect fun <T : Any> BoxScope.TuiDropdownMenu(
    dropdownState: TuiDropdownState,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
    positionProvider: TuiPopupPositionProvider = TuiPopupPositionProvider.AboveStart,
    backgroundColor: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
): Unit

/** Framework-only exclusive-choice button and wrapped description; owns no business state. */
@Composable
public expect fun InteractionOption(
    label: String,
    description: String,
    columns: Int,
    selected: Boolean,
    enabled: Boolean,
    autoFocus: Boolean = false,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
): Unit

/**
 * Renderer buffer keyed by exact owner/input identity. [text] remains authoritative;
 * input edits and Enter are callbacks, never a hidden business draft or auto-submission.
 */
@Composable
public expect fun InteractionFreeForm(
    ownerKey: Any,
    inputId: String,
    text: String,
    columns: Int,
    autoFocus: Boolean,
    focusRequester: FocusRequester,
    focusOnPlacement: Boolean,
    enabled: Boolean,
    allowEmpty: Boolean = false,
    onValueChanged: (String) -> Unit,
    onSubmitted: () -> Unit,
    onFocusRequested: () -> Unit,
): Unit

/** Wrapped text with a minimum one-cell width; no domain formatting or state. */
@Composable
public expect fun InteractionText(value: String, columns: Int, textStyle: TextStyle): Unit
