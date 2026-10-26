package io.github.stream29.kodex.app.ui

import androidx.compose.runtime.Composable

public enum class UiTextRole { Body, Title, Supporting, Error }
public enum class UiActionRole { Normal, Primary, Secondary, Destructive }
/** Multiline newline chord: Shift+Enter for [Enter], plain Enter for [CtrlEnter]. */
public enum class UiSubmitKey { Enter, CtrlEnter }

/** Installs renderer theme and packaged graphical fallback fonts before mounting its content. */
@Composable
public expect fun UiTheme(content: @Composable () -> Unit): Unit

/** A renderer-owned vertical region; no terminal cells or pixel constraints escape this boundary. */
@Composable
public expect fun UiColumn(content: @Composable () -> Unit): Unit

/** Trailing command region with renderer-appropriate spacing and focus traversal. */
@Composable
public expect fun UiActions(content: @Composable () -> Unit): Unit

@Composable
public expect fun UiText(
    text: String, role: UiTextRole = UiTextRole.Body, maximumLines: Int = Int.MAX_VALUE,
): Unit

/** Complete read-only text, wrapped and selectable using the renderer's own measurement. */
@Composable
public expect fun UiProse(text: String, role: UiTextRole = UiTextRole.Body): Unit

/** Keyboard and pointer activation share one command; disabled controls cannot admit it. */
@Composable
public expect fun UiAction(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    autoFocus: Boolean = false,
    role: UiActionRole = UiActionRole.Normal,
): Unit

/**
 * Edits externally owned text and its active UTF-16 cursor. Selection, composition, undo,
 * focus and viewport remain in the renderer. Equal external values must not reset IME composition.
 * Enter used to commit composition must not submit. A changed owner must remount this widget.
 * Read-only permits navigation but neither mutation nor submission.
 * Submit synchronously supplies the current widget buffer, even before its external echo renders.
 * When [revision] is supplied, [onChange] returns the component's accepted revision; older echoes
 * cannot roll back a newer local edit. Revisions are monotonic within one mounted owner.
 */
@Composable
public expect fun UiEditor(
    text: String,
    cursorOffset: Int,
    onChange: (String, Int) -> Long?,
    onSubmit: (text: String, cursorOffset: Int) -> Unit,
    label: String = "",
    enabled: Boolean = true,
    readOnly: Boolean = false,
    autoFocus: Boolean = false,
    multiline: Boolean = true,
    maximumLines: Int = 6,
    submitKey: UiSubmitKey = UiSubmitKey.Enter,
    revision: Long? = null,
): Unit

/**
 * Single-row value field with renderer-owned cursor and selection.
 * [onChange] synchronously updates the owning draft; async/revisioned editors use [UiEditor].
 * An owner change must remount this field. Optional focus uses [WithUiEditorFocus].
 * Reuses [UiEditor]'s paste semantics; a single row does not strip embedded newlines.
 */
@Composable
public expect fun UiTextInput(
    text: String,
    onChange: (String) -> Unit,
    label: String,
    enabled: Boolean = true,
    autoFocus: Boolean = false,
    onSubmit: () -> Unit = {},
): Unit

@Composable
internal expect fun TextInputFrame(label: String, content: @Composable () -> Unit): Unit

/** Value-field leaf for a rendering surface; callbacks retain [UiTextInput]'s synchronous contract. */
public interface UiTextInputRenderer {
    @Composable
    public fun Field(
        text: String, onChange: (String) -> Unit, label: String,
        enabled: Boolean, autoFocus: Boolean, onSubmit: () -> Unit,
    ): Unit
}

/**
 * Supplies value-field leaves for a scoped surface, such as a raster-only scene without DOM interop.
 * Interactive hosts omit this provider and use the platform [UiEditor]. No business state is replaced.
 */
@Composable
public expect fun WithUiTextInputRenderer(
    renderer: UiTextInputRenderer, content: @Composable () -> Unit,
): Unit
