package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.*
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.layout.*
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import io.github.stream29.kodex.app.application.contract.TerminalOutputItem
import io.github.stream29.kodex.app.application.contract.TerminalOutputState
import io.github.stream29.kodex.cli.components.*
import io.github.stream29.kodex.utils.terminaltext.takeFirstFittingTerminalWidth
import io.github.stream29.kodex.utils.terminaltext.terminalCellWidth
import kotlinx.coroutines.flow.StateFlow

internal object MosaicTerminalOutputPlacement : SidebarShellOutputPlacement

@Composable
internal fun BoxScope.MosaicTerminalHover(
    anchor: TuiPopupAnchor, side: SessionSidebarSide, columns: Int, rows: Int,
    preview: SidebarShellPreview, output: StateFlow<TerminalOutputState>, onHoverChanged: (Boolean) -> Unit,
) {
    val state by output.collectAsState()
    val colors = TuiTheme.colorScheme
    val details = remember(preview) {
        (preview.metadataLines + preview.command).joinToString("\n") { source ->
            buildString {
                source.forEach {
                    when (it) {
                        '\n', '\r' -> append(it)
                        '\t' -> append("    ")
                        in '\u0000'..'\u001f', in '\u007f'..'\u009f' -> append('�')
                        else -> append(it)
                    }
                }
            }
        }
    }
    val width = remember(preview, details, columns) {
        maxOf(36, preview.title.terminalCellWidth(), details.lineSequence().maxOf(String::terminalCellWidth))
            .coerceAtMost(columns)
    }
    val lines = remember(details, width) { details.wrapToTerminalWidth(width) }
    // Reserve space for the live tail without hiding a long command: both panes scroll independently.
    val height = (lines.size + 6).coerceAtMost(rows)
    val detailsHeight = if (height < 4) (height - 1).coerceAtLeast(0) else minOf(lines.size, (height - 1) / 2)
    val showStatus = height - detailsHeight >= 2
    val outputHeight = (height - 1 - detailsHeight - if (showStatus) 1 else 0).coerceAtLeast(0)
    val layout = remember(state, width) { TerminalCellLines(state.items, width) }
    var pausedAt by remember(anchor) { mutableStateOf<TerminalLineAnchor?>(null) }
    val start = layout.start(pausedAt, outputHeight)
    TuiPopup(
        anchor = anchor,
        onDismissRequest = null,
        positionProvider = remember(side, columns, rows) { SidebarHoverPopupPositionProvider(side, columns, rows) },
        modifier = Modifier.width(width).height(height)
            .drawBehind { drawRect(char = ' ', textStyle = TextStyle.Empty) }
            .background(colors.surfaceContainer)
            .onPointerHover(onPointerEnter = { onHoverChanged(true) }, onPointerExit = { onHoverChanged(false) }),
    ) {
        Column(Modifier.fillMaxSize()) {
            EllipsizedText(
                preview.title, Modifier.fillMaxWidth().background(colors.surfaceContainerHigh), color = colors.onSurface,
            )
            if (detailsHeight > 0) {
                LazyColumn(Modifier.fillMaxWidth().height(detailsHeight)) {
                    items(lines) { line -> Text(line.ifEmpty { " " }, color = colors.onSurface) }
                }
            }
            if (showStatus) EllipsizedText(
                terminalOutputStatus(state.phase, state.items.isNotEmpty()), Modifier.fillMaxWidth(),
                color = colors.onSurfaceVariant,
            )
            if (start != null) Column(
                Modifier.fillMaxWidth().height(outputHeight).onPointerEvent { event ->
                    if (event.type != MouseEvent.Type.Press) return@onPointerEvent false
                    val delta = when (event.button) {
                        MouseEvent.Button.WheelUp -> -3
                        MouseEvent.Button.WheelDown -> 3
                        else -> return@onPointerEvent false
                    }
                    val next = layout.scroll(start, delta, outputHeight)
                    if (delta > 0 && next == layout.start(null, outputHeight)) pausedAt = null
                    else if (next != start) pausedAt = layout.anchor(next)
                    next != start
                },
            ) {
                layout.visible(start, outputHeight).forEach { line ->
                    Text(
                        line.text,
                        color = if (line.marker) colors.onSurfaceVariant else colors.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
internal fun BoxScope.MosaicTerminalOutput(
    columns: Int, rows: Int, title: String, command: String, output: StateFlow<TerminalOutputState>, onBack: () -> Unit,
) {
    val state by output.collectAsState()
    val width = (columns - 2).coerceIn(1, 84)
    val height = (rows - 2).coerceIn(1, 36)
    val showTitle = height >= 3
    val status = height >= 4
    val showCommand = height >= 6
    var pausedAt by remember { mutableStateOf<TerminalLineAnchor?>(null) }
    val affordance = pausedAt != null && height >= 4
    val bodyHeight = (height - 1 - listOf(showTitle, status, showCommand, affordance).count { it }).coerceAtLeast(0)
    val layout = remember(state, width) { TerminalCellLines(state.items, width) }
    val start = layout.start(pausedAt, bodyHeight)
    val back = remember { FocusRequester() }
    val commandLine = remember(command) {
        command.map { if (it in '\u0000'..'\u001f' || it in '\u007f'..'\u009f') ' ' else it }.joinToString("")
    }
    LaunchedEffect(back) {
        repeat(3) {
            withFrameNanos { }
            if (back.requestFocus()) return@LaunchedEffect
        }
    }
    fun scroll(delta: Int): Boolean {
        val current = start ?: return false
        val next = layout.scroll(current, delta, bodyHeight)
        if (next == current) return false
        pausedAt = layout.anchor(next)
        return true
    }
    fun page(event: KeyEvent): Boolean {
        if (event.alt || event.ctrl || event.shift) return false
        val direction = when (event.key) {
            "PageUp" -> -1
            "PageDown" -> 1
            else -> return false
        }
        scroll(direction * bodyHeight.coerceAtLeast(1))
        return true
    }
    TuiDialog(
        onDismissRequest = onBack,
        modifier = Modifier.width(width).height(height).background(TuiTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.fillMaxSize().onPreviewKeyEvent(::page)) {
            if (showTitle) EllipsizedText(
                title,
                Modifier.fillMaxWidth().background(TuiTheme.colorScheme.surfaceContainerHigh),
                color = TuiTheme.colorScheme.onSurface,
            )
            if (showCommand) EllipsizedText(commandLine, Modifier.fillMaxWidth(), color = TuiTheme.colorScheme.onSurface)
            if (status) EllipsizedText(
                terminalOutputStatus(state.phase, state.items.isNotEmpty()), Modifier.fillMaxWidth(),
                color = TuiTheme.colorScheme.onSurface,
            )
            if (bodyHeight > 0) Box(
                Modifier.fillMaxWidth().height(bodyHeight).onPointerEvent { event ->
                    if (event.type != MouseEvent.Type.Press) return@onPointerEvent false
                    when (event.button) {
                        MouseEvent.Button.WheelUp -> scroll(-3)
                        MouseEvent.Button.WheelDown -> scroll(3)
                        else -> false
                    }
                },
            ) {
                Column {
                    if (start != null) layout.visible(start, bodyHeight).forEach { line ->
                        Text(
                            line.text,
                            color = if (line.marker) TuiTheme.colorScheme.onSurfaceVariant else TuiTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            if (affordance) MosaicHistorySeparatorRenderer(width).Separator(null, true) {
                back.requestFocus()
                pausedAt = null
            }
            TuiButton("Back", focusRequester = back, onKeyEvent = ::page, autoFocus = true, onClick = onBack)
        }
    }
}

private data class TerminalLineAnchor(val id: Long, val line: Int)
private data class TerminalCellPosition(val index: Int, val line: Int) : Comparable<TerminalCellPosition> {
    override fun compareTo(other: TerminalCellPosition): Int =
        if (index != other.index) index.compareTo(other.index) else line.compareTo(other.line)
}
private data class TerminalCellLine(val text: String, val marker: Boolean)

/** Wrap only records in or immediately adjacent to the bounded viewport; do not lay out the retained log. */
private class TerminalCellLines(private val items: List<TerminalOutputItem>, private val width: Int) {
    private val lines = LinkedHashMap<Int, List<String>>()
    private fun at(index: Int): List<String> {
        lines[index]?.let { return it }
        // Avoid retaining earlier pages' projections while following or paging a single snapshot.
        if (lines.size >= 96) lines.remove(lines.keys.first())
        return terminalOutputItemLabel(items[index]).wrapToTerminalWidth(width)
            .ifEmpty { listOf("") }.also { lines[index] = it }
    }
    private fun advance(position: TerminalCellPosition, forward: Boolean): TerminalCellPosition? {
        val (index, line) = position
        return if (forward) {
            if (line < at(index).lastIndex) position.copy(line = line + 1)
            else if (index < items.lastIndex) TerminalCellPosition(index + 1, 0) else null
        } else {
            if (line > 0) position.copy(line = line - 1)
            else if (index > 0) TerminalCellPosition(index - 1, at(index - 1).lastIndex) else null
        }
    }
    private fun terminalStart(height: Int): TerminalCellPosition {
        var edge = TerminalCellPosition(items.lastIndex, at(items.lastIndex).lastIndex)
        repeat((height - 1).coerceAtLeast(0)) { edge = advance(edge, false) ?: return edge }
        return edge
    }
    fun start(saved: TerminalLineAnchor?, height: Int): TerminalCellPosition? {
        if (items.isEmpty() || height <= 0) return null
        val end = terminalStart(height)
        if (saved == null) return end
        val index = items.binarySearchBy(saved.id) { it.id }
        if (index < 0) return TerminalCellPosition(0, 0)
        return minOf(TerminalCellPosition(index, saved.line.coerceAtMost(at(index).lastIndex)), end)
    }
    fun scroll(from: TerminalCellPosition, delta: Int, height: Int): TerminalCellPosition {
        var target = from
        repeat(kotlin.math.abs(delta)) { target = advance(target, delta > 0) ?: return minOf(target, terminalStart(height)) }
        return minOf(target, terminalStart(height))
    }
    fun anchor(position: TerminalCellPosition): TerminalLineAnchor =
        TerminalLineAnchor(items[position.index].id, position.line)

    fun visible(from: TerminalCellPosition, height: Int): List<TerminalCellLine> = buildList {
        var position = from
        repeat(height) {
            val text = at(position.index)[position.line].takeFirstFittingTerminalWidth(width).ifEmpty { " " }
            add(TerminalCellLine(text, items[position.index] !is TerminalOutputItem.Text))
            position = advance(position, true) ?: return@buildList
        }
    }
}
