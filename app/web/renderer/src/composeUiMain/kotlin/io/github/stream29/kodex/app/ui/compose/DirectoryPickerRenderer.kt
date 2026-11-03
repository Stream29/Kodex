@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.app.ui.compose.EditorDialog as ComposeDialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.stream29.kodex.app.ui.*
import io.github.stream29.kodex.cli.pathpicker.*

@Composable
public actual fun rememberComposeDirectoryPickerRenderer(): DirectoryPickerRenderer = remember { ComposeDirectoryPicker() }

private class ComposeDirectoryPicker : DirectoryPickerRenderer {
    @Composable
    override fun Dialog(
        presentation: DirectoryPickerPresentation, onDismiss: () -> Unit, onEscape: () -> Unit, onConfirm: () -> Unit,
        onNavigateUp: () -> Unit, onFilter: (String) -> Unit, onFilterLetter: (String) -> Unit, onBackspace: () -> Boolean,
    ) {
        ComposeDialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(dismissOnBackPress = false, usePlatformDefaultWidth = false),
        ) {
            val list = rememberLazyListState()
            val selectFocus = remember { FocusRequester() }
            val firstMatchKey = presentation.rows.firstOrNull { it.focusForFilter }?.key
            val firstMatchFocus = remember(firstMatchKey) { FocusRequester() }
            val noMatch = presentation.filter.isNotEmpty() &&
                presentation.rows.any { it.key == "empty" && it.role == DirectoryPickerRowRole.Text }
            val noMatchFocus = remember(noMatch) { FocusRequester() }
            val latestFilter by rememberUpdatedState(onFilter)
            val latestFilterLetter by rememberUpdatedState(onFilterLetter)
            val latestBackspace by rememberUpdatedState(onBackspace)
            val latestConfirm by rememberUpdatedState(onConfirm)

            LaunchedEffect(presentation.listingIdentity, presentation.filter, firstMatchKey, noMatch) {
                list.requestScrollToItem(0)
                val target = when {
                    presentation.filter.isEmpty() -> selectFocus
                    firstMatchKey != null -> firstMatchFocus
                    noMatch -> noMatchFocus
                    else -> null
                }
                if (target != null) {
                    repeat(2) {
                        withFrameNanos { }
                        if (try { target.requestFocus() } catch (_: IllegalStateException) { false }) return@LaunchedEffect
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) {
                            return@onPreviewKeyEvent false
                        }
                        when {
                            event.key == Key.Backspace -> latestBackspace()
                            else -> {
                                val letter = event.filterLetterOrNull()
                                if (letter != null) {
                                    latestFilterLetter(letter)
                                    true
                                } else {
                                    false
                                }
                            }
                        }
                    },
            ) {
                Column(
                    Modifier.widthIn(min = 360.dp, max = 400.dp).heightIn(max = 640.dp).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    UiText(presentation.title, UiTextRole.Title)
                    // Top Bar: Current Path Box + Right-aligned Select button (Plan B)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.weight(1f)
                                .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(4.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                        ) {
                            SelectionContainer {
                                UiText(presentation.directory, maximumLines = 1)
                            }
                        }
                        Box(Modifier.focusRequester(selectFocus)) {
                            UiAction(
                                presentation.selectLabel,
                                onClick = { latestConfirm() },
                                autoFocus = true,
                                role = UiActionRole.Primary,
                            )
                        }
                    }

                    // Directory List Pane with embedded IDEA Speed Search header
                    val paneBorderColor = when {
                        noMatch -> MaterialTheme.colorScheme.error
                        presentation.filter.isNotEmpty() -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.outlineVariant
                    }
                    val filterHeaderBg = when {
                        noMatch -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
                        presentation.filter.isNotEmpty() -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        else -> MaterialTheme.colorScheme.surfaceContainer
                    }
                    val matchCount = presentation.rows.count { it.role == DirectoryPickerRowRole.Directory }

                    Column(
                        Modifier.fillMaxWidth()
                            .weight(1f, fill = false)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(4.dp))
                            .border(1.dp, paneBorderColor, RoundedCornerShape(4.dp)),
                    ) {
                        Row(
                            Modifier.fillMaxWidth()
                                .background(filterHeaderBg)
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                                .semantics {
                                    contentDescription = "Directory filter"
                                    editableText = AnnotatedString(presentation.filter)
                                    setText {
                                        latestFilter(it.text)
                                        true
                                    }
                                },
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SearchVectorIcon(
                                color = when {
                                    noMatch -> MaterialTheme.colorScheme.error
                                    presentation.filter.isNotEmpty() -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Text(
                                text = "Filter: ${presentation.filter.ifEmpty { "type letters" }}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (presentation.filter.isEmpty()) FontWeight.Normal else FontWeight.SemiBold,
                                color = when {
                                    noMatch -> MaterialTheme.colorScheme.error
                                    presentation.filter.isNotEmpty() -> MaterialTheme.colorScheme.onSurface
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (presentation.filter.isNotEmpty()) {
                                Text(
                                    text = if (noMatch) "0 matches" else "$matchCount ${if (matchCount == 1) "match" else "matches"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (noMatch) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        LazyColumn(
                            state = list,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 260.dp).padding(4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            items(presentation.rows, key = DirectoryPickerRow::key) { row ->
                                when (row.role) {
                                    DirectoryPickerRowRole.Parent, DirectoryPickerRowRole.Directory -> {
                                        DirectoryListRow(
                                            row = row,
                                            focusRequester = if (row.focusForFilter) firstMatchFocus else null,
                                        )
                                    }
                                    DirectoryPickerRowRole.Text -> {
                                        if (row.key == "empty" && noMatch) {
                                            NoMatchDirectoryRow(label = row.label, focusRequester = noMatchFocus)
                                        } else {
                                            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                                                UiText(row.label, UiTextRole.Supporting)
                                            }
                                        }
                                    }
                                    DirectoryPickerRowRole.Error -> {
                                        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                                            UiText(row.label, UiTextRole.Error)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Bottom Bar
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Enter: open / confirm · Esc: clear / close",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        UiAction(presentation.cancelLabel, onClick = onDismiss)
                    }
                }
            }
            NavigationBackHandler(
                state = rememberNavigationEventState(NavigationEventInfo.None), onBackCompleted = onEscape,
            )
        }
    }
}

@Composable
private fun DirectoryListRow(
    row: DirectoryPickerRow,
    focusRequester: FocusRequester?,
) {
    val activate by rememberUpdatedState(row.onActivate)
    var isFocused by remember { mutableStateOf(false) }
    var isHovered by remember { mutableStateOf(false) }
    val bgColor = when {
        isFocused -> MaterialTheme.colorScheme.primaryContainer
        isHovered -> MaterialTheme.colorScheme.surfaceContainerHighest
        else -> Color.Transparent
    }
    val borderColor = if (isFocused) MaterialTheme.colorScheme.primary else Color.Transparent
    val contentColor = if (isFocused) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 28.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor, RoundedCornerShape(4.dp))
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { isFocused = it.isFocused }
            .onPointerEvent(PointerEventType.Enter) { isHovered = true }
            .onPointerEvent(PointerEventType.Exit) { isHovered = false }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.Spacebar)
                ) {
                    activate?.invoke()
                    true
                } else {
                    false
                }
            }
            .clickable(role = Role.Button) { activate?.invoke() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.role == DirectoryPickerRowRole.Parent) {
            ParentDirectoryVectorIcon(color = contentColor)
        } else {
            FolderVectorIcon(color = if (isFocused) contentColor else MaterialTheme.colorScheme.primary)
        }
        Text(
            text = row.label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun NoMatchDirectoryRow(
    label: String,
    focusRequester: FocusRequester,
) {
    var isFocused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 28.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
            .border(
                width = 1.dp,
                color = if (isFocused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                shape = RoundedCornerShape(4.dp),
            )
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused }
            .onPreviewKeyEvent { event ->
                // Consume Enter/Space when focused on no-match row so it never triggers parent Select.
                event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.Spacebar)
            }
            .focusable()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.error,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SearchVectorIcon(color: Color) {
    Canvas(Modifier.size(14.dp)) {
        val stroke = 1.5.dp.toPx()
        val r = size.minDimension * 0.34f
        val center = Offset(size.width * 0.42f, size.height * 0.42f)
        drawCircle(color = color, radius = r, center = center, style = Stroke(width = stroke))
        drawLine(
            color = color,
            start = Offset(center.x + r * 0.72f, center.y + r * 0.72f),
            end = Offset(size.width * 0.88f, size.height * 0.88f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun ParentDirectoryVectorIcon(color: Color) {
    Canvas(Modifier.size(14.dp)) {
        val stroke = 1.5.dp.toPx()
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.75f, h * 0.72f)
            lineTo(w * 0.35f, h * 0.72f)
            lineTo(w * 0.35f, h * 0.22f)
        }
        drawPath(path, color = color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        val arrow = Path().apply {
            moveTo(w * 0.16f, h * 0.42f)
            lineTo(w * 0.35f, h * 0.22f)
            lineTo(w * 0.54f, h * 0.42f)
        }
        drawPath(arrow, color = color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun FolderVectorIcon(color: Color) {
    Canvas(Modifier.size(14.dp)) {
        val stroke = 1.4.dp.toPx()
        val w = size.width
        val h = size.height
        val folder = Path().apply {
            moveTo(w * 0.10f, h * 0.26f)
            lineTo(w * 0.40f, h * 0.26f)
            lineTo(w * 0.50f, h * 0.38f)
            lineTo(w * 0.90f, h * 0.38f)
            lineTo(w * 0.90f, h * 0.80f)
            lineTo(w * 0.10f, h * 0.80f)
            close()
        }
        drawPath(folder, color = color.copy(alpha = 0.20f))
        drawPath(folder, color = color, style = Stroke(width = stroke, join = StrokeJoin.Round))
    }
}

private fun KeyEvent.filterLetterOrNull(): String? {
    val codePoint = utf16CodePoint
    if (codePoint > 0) {
        val ch = Char(codePoint)
        return if (ch.isLetter()) ch.toString() else null
    }
    val base = when (key) {
        Key.A -> 'a'
        Key.B -> 'b'
        Key.C -> 'c'
        Key.D -> 'd'
        Key.E -> 'e'
        Key.F -> 'f'
        Key.G -> 'g'
        Key.H -> 'h'
        Key.I -> 'i'
        Key.J -> 'j'
        Key.K -> 'k'
        Key.L -> 'l'
        Key.M -> 'm'
        Key.N -> 'n'
        Key.O -> 'o'
        Key.P -> 'p'
        Key.Q -> 'q'
        Key.R -> 'r'
        Key.S -> 's'
        Key.T -> 't'
        Key.U -> 'u'
        Key.V -> 'v'
        Key.W -> 'w'
        Key.X -> 'x'
        Key.Y -> 'y'
        Key.Z -> 'z'
        else -> return null
    }
    return (if (isShiftPressed) base.uppercaseChar() else base).toString()
}
