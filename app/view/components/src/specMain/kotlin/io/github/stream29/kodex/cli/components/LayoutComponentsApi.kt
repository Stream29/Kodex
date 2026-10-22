package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.UnderlineStyle
import com.jakewharton.mosaic.ui.unit.IntOffset
import kotlinx.datetime.TimeZone
import kotlinx.io.files.Path
import kotlin.time.Instant

/** Renders one hard line, adding an ellipsis when it exceeds the measured terminal-cell width. */
@Composable
public expect fun EllipsizedText(
    value: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    background: Color = Color.Unspecified,
    textStyle: TextStyle = TextStyle.Unspecified,
    underlineStyle: UnderlineStyle = UnderlineStyle.Unspecified,
    underlineColor: Color = Color.Unspecified,
): Unit

/**
 * Renders one ellipsized hard line followed immediately by independently measured content.
 *
 * The trailing content is measured first and remains visible while [value] receives only the
 * remaining bounded width.
 */
@Composable
public expect fun EllipsizedTextWithTrailingContent(
    value: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    background: Color = Color.Unspecified,
    textStyle: TextStyle = TextStyle.Unspecified,
    underlineStyle: UnderlineStyle = UnderlineStyle.Unspecified,
    underlineColor: Color = Color.Unspecified,
    trailingContent: @Composable () -> Unit,
): Unit

/**
 * Measures content eagerly and exposes a horizontally scrollable terminal-cell viewport.
 *
 * The horizontal axis must have a finite maximum width. Wheel input moves by terminal columns and
 * unmodified PageUp/PageDown move by one viewport.
 */
public expect fun Modifier.horizontalScroll(
    state: ScrollState,
    enabled: Boolean = true,
    reverseScrolling: Boolean = false,
    interactionSource: MutableScrollInteractionSource? = null,
): Modifier

/**
 * A vertically scrolling list which composes only the items around its measured viewport.
 *
 * The vertical axis must have a finite maximum height. Items may contain any Mosaic composables
 * and may occupy different numbers of terminal rows.
 *
 * When [reverseLayout] is true, logical item `0` is placed at the visual bottom and increasing
 * item indexes extend upward. Stable keys continue to identify the same logical items.
 *
 * @param interactionSource `null` when the caller does not observe scroll interactions.
 * @param keyboardPageSize Maps the measured viewport to the Page Up and Page Down row count.
 */
@Composable
public expect fun LazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    reverseLayout: Boolean = false,
    userScrollEnabled: Boolean = true,
    interactionSource: MutableScrollInteractionSource? = null,
    keyboardPageSize: (viewportSize: Int) -> Int = { it },
    content: LazyListScope.() -> Unit,
): Unit

/** Shared by Agent controls and draft statusbar visual buttons. */
@get:Composable
@get:ReadOnlyComposable
public expect val SessionButtonForeground: Color

@get:Composable
@get:ReadOnlyComposable
public expect val SessionButtonBackground: Color

/** Shared by Sidebar popup surfaces and the root's history confirmation surface. */
@get:Composable
@get:ReadOnlyComposable
public expect val SettingsDialogHomeBackground: Color

/**
 * Interprets wheel input as terminal-cell deltas along [orientation] for [state].
 * Native horizontal wheel input is consumed only by horizontal scrollables; vertical wheel input
 * remains a compatibility input for either logical axis.
 *
 * This modifier does not move, measure, or clip content. It consumes a wheel event only when
 * [ScrollableState.scrollBy] consumes a non-zero delta.
 *
 * @param interactionSource `null` when the caller does not observe scroll interactions.
 */
public expect fun Modifier.scrollable(
    state: ScrollableState,
    orientation: ScrollOrientation = ScrollOrientation.Vertical,
    enabled: Boolean = true,
    reverseDirection: Boolean = false,
    interactionSource: MutableScrollInteractionSource? = null,
    wheelScrollLines: Int = 3,
): Modifier

/**
 * Interprets unmodified Page Up and Page Down keys as viewport-sized scroll requests.
 *
 * [viewportSize] should report the latest measured viewport and is read when the key event arrives.
 * [pageSize] maps that viewport to the requested cell distance. The event is consumed only when
 * [state] consumes a non-zero distance.
 *
 * @param interactionSource `null` when the caller does not observe scroll interactions.
 */
public expect fun Modifier.scrollablePaging(
    state: ScrollableState,
    viewportSize: () -> Int,
    pageSize: (viewportSize: Int) -> Int = { it },
    orientation: ScrollOrientation = ScrollOrientation.Vertical,
    enabled: Boolean = true,
    reverseDirection: Boolean = false,
    interactionSource: MutableScrollInteractionSource? = null,
): Modifier

/**
 * Measures all content eagerly and exposes a vertically scrollable viewport.
 *
 * The vertical axis must have a finite maximum height. Programmatic changes to [state] continue to
 * affect placement when [enabled] is false; that flag only disables user wheel input.
 *
 * @param interactionSource `null` when the caller does not observe scroll interactions.
 */
public expect fun Modifier.verticalScroll(
    state: ScrollState,
    enabled: Boolean = true,
    reverseScrolling: Boolean = false,
    interactionSource: MutableScrollInteractionSource? = null,
): Modifier

/** Shared visual formatting only; acquiring/invalidating a timestamp belongs to its state owner. */
public expect fun formatPopupTimestamp(timestamp: Instant, timeZone: TimeZone): String

public expect val RunningIndicatorFrames: List<String>

public const val RunningIndicatorFrameDurationMillis: Int = 100

@Composable
public expect fun rememberRunningIndicatorFrame(active: Boolean): State<String>

public expect fun runningIndicatorLabel(
    name: String,
    running: Boolean,
    frame: String,
): String

@Composable
public expect fun SettingsStatusButton(onOpenSettings: () -> Unit): Unit

@Composable
public expect fun WorkingDirectoryStatusButton(
    columns: Int,
    workingDirectory: Path,
    enabled: Boolean,
    onBrowse: () -> Unit,
): Unit

public expect fun workingDirectoryStatusLabel(
    workingDirectory: Path,
    columns: Int,
): String

public const val SettingsLabel: String = "Settings"

/** Pure measured-content layout shared by Agent and draft status bars. */
@Composable
public expect fun StatusBarLayout(
    columns: Int,
    regularContent: @Composable () -> Unit,
    settingsContent: (@Composable () -> Unit)? = null,
): Unit

public expect fun buttonWidth(label: String): Int

public expect fun statusBarWidth(columns: Int): Int

public data class StatusBarLayoutPlan(
    public val itemPositions: List<IntOffset>,
    public val settingsPosition: IntOffset,
    public val rowCount: Int,
)

public expect fun statusBarLayoutPlan(
    width: Int,
    itemWidths: List<Int>,
    settingsWidth: Int,
): StatusBarLayoutPlan

/** Wraps hard lines to terminal-cell [width] without splitting a grapheme cluster. */
public expect fun String.wrapToTerminalWidth(width: Int): List<String>

/** Truncates this text to [maximumWidth] terminal cells, appending an ellipsis when possible. */
public expect fun String.ellipsizeToTerminalWidth(maximumWidth: Int): String
