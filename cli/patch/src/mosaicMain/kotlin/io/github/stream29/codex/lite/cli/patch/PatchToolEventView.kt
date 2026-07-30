package io.github.stream29.codex.lite.cli.patch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.text.AnnotatedString
import com.jakewharton.mosaic.text.SpanStyle
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.text.withStyle
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.SubcomposeLayout
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.unit.Constraints
import com.jakewharton.mosaic.ui.unit.constrainHeight
import com.jakewharton.mosaic.ui.unit.constrainWidth
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StablePatchToolEvent
import io.github.stream29.codex.lite.cli.components.TuiPressable
import io.github.stream29.codex.lite.utils.applypatch.Patch
import io.github.stream29.codex.lite.utils.terminaltext.takeFirstFittingTerminalWidth
import io.github.stream29.codex.lite.utils.terminaltext.terminalCellWidth

private const val DefaultPatchLinePageSize: Int = 200

/** Renders an apply-patch invocation whose result is still pending. */
@Composable
public fun PendingPatchToolEventView(diff: Patch) {
    val presentation = remember(diff) {
        diff.toPendingPatchPresentation()
    }
    val expanded = remember(diff) { mutableStateOf(false) }
    val changesExpanded = remember(diff) { mutableStateOf(false) }
    val visibleLineCount = remember(diff) { mutableStateOf(DefaultPatchLinePageSize) }
    PatchToolEventView(
        presentation = presentation,
        expandedState = expanded,
        changesExpandedState = changesExpanded,
        visibleLineCountState = visibleLineCount,
    )
}

/** Renders a completed apply-patch clean event. */
@Composable
public fun StablePatchToolEventView(event: StablePatchToolEvent) {
    val presentation = remember(event) {
        event.toStablePatchPresentation()
    }
    val expanded = remember(event) { mutableStateOf(false) }
    val changesExpanded = remember(event) { mutableStateOf(false) }
    val visibleLineCount = remember(event) { mutableStateOf(DefaultPatchLinePageSize) }
    PatchToolEventView(
        presentation = presentation,
        expandedState = expanded,
        changesExpandedState = changesExpanded,
        visibleLineCountState = visibleLineCount,
    )
}

@Composable
private fun PatchToolEventView(
    presentation: PatchPresentation,
    expandedState: MutableState<Boolean>,
    changesExpandedState: MutableState<Boolean>,
    visibleLineCountState: MutableState<Int>,
) {
    val expanded by expandedState
    Column(modifier = Modifier.fillMaxWidth()) {
        TuiPressable(
            onClick = { expandedState.value = !expanded },
            modifier = Modifier.fillMaxWidth(),
        ) { isFocused, isHovered, isPressed ->
            WrappedPatchText(
                value = "${if (expanded) "v" else ">"} ${presentation.header}",
                color = when (presentation.status) {
                    PatchPresentationStatus.Running -> Color.Green
                    PatchPresentationStatus.Completed -> Color.White
                    PatchPresentationStatus.Failed -> Color.Red
                },
                textStyle = when {
                    isPressed -> TextStyle.Invert
                    isFocused || isHovered -> TextStyle.Bold
                    else -> TextStyle.Unspecified
                },
            )
        }

        if (expanded) {
            WrappedPatchText(
                value = "Tool: ${presentation.rawToolName}",
                textStyle = TextStyle.Dim,
            )
            PatchChangeDetails(
                presentation = presentation,
                expandedState = changesExpandedState,
                visibleLineCountState = visibleLineCountState,
            )
        }
    }
}

@Composable
private fun PatchChangeDetails(
    presentation: PatchPresentation,
    expandedState: MutableState<Boolean>,
    visibleLineCountState: MutableState<Int>,
) {
    val expanded by expandedState
    val visibleLineCount by visibleLineCountState
    TuiPressable(
        onClick = { expandedState.value = !expanded },
        modifier = Modifier.fillMaxWidth(),
    ) { isFocused, isHovered, isPressed ->
        WrappedPatchText(
            value = "${if (expanded) "v" else ">"} Changes",
            textStyle = when {
                isPressed -> TextStyle.Invert
                isFocused || isHovered -> TextStyle.Bold
                else -> TextStyle.Unspecified
            },
        )
    }
    PatchBody(
        presentation = presentation,
        expandedState = expandedState,
        visibleLineCountState = visibleLineCountState,
    )
    PatchShowMore(
        presentation = presentation,
        expandedState = expandedState,
        visibleLineCountState = visibleLineCountState,
    )
}

@Composable
private fun PatchShowMore(
    presentation: PatchPresentation,
    expandedState: State<Boolean>,
    visibleLineCountState: MutableState<Int>,
) {
    SubcomposeLayout(modifier = Modifier.fillMaxWidth()) { constraints ->
        val visibleLineCount = visibleLineCountState.value
        val remainingLineCount = if (expandedState.value) {
            presentation.lines.size - presentation.lines.take(visibleLineCount).size
        } else {
            0
        }
        val placeable = subcompose(PatchShowMoreSlot(remainingLineCount)) {
            TuiPressable(
                onClick = {
                    visibleLineCountState.value = minOf(
                        visibleLineCount + DefaultPatchLinePageSize,
                        presentation.lines.size,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = remainingLineCount > 0,
            ) { isFocused, isHovered, isPressed ->
                WrappedPatchText(
                    value = "> Show next ${minOf(DefaultPatchLinePageSize, remainingLineCount)} lines" +
                        " · $remainingLineCount remaining",
                    textStyle = when {
                        isPressed -> TextStyle.Invert
                        isFocused || isHovered -> TextStyle.Bold
                        else -> TextStyle.Dim
                    },
                )
            }
        }.single().measure(
            constraints.copy(
                minWidth = 0,
                minHeight = 0,
                maxHeight = Constraints.Infinity,
            ),
        )
        val height = if (remainingLineCount > 0) {
            constraints.constrainHeight(placeable.height)
        } else {
            0
        }
        layout(
            width = constraints.constrainWidth(placeable.width),
            height = height,
        ) {
            if (remainingLineCount > 0) {
                placeable.place(0, 0)
            }
        }
    }
}

@Composable
private fun PatchBody(
    presentation: PatchPresentation,
    expandedState: State<Boolean>,
    visibleLineCountState: State<Int>,
) {
    SubcomposeLayout(modifier = Modifier.fillMaxWidth()) { constraints ->
        check(constraints.hasBoundedWidth) {
            "Patch text must be measured with a finite maximum width."
        }
        val lines = if (expandedState.value) {
            presentation.lines.take(visibleLineCountState.value)
        } else {
            emptyList()
        }
        val styledLines = lines.map { line ->
            StyledPatchLine(
                text = line.text,
                style = SpanStyle(
                    color = line.kind.color(),
                    textStyle = line.kind.textStyle(),
                ),
            )
        }
        val width = constraints.maxWidth.coerceAtLeast(1)
        val text = styledLines.toWrappedAnnotatedString(width)
        val placeable = subcompose(PatchBodySlot(text)) {
            Text(text)
        }.single().measure(
            constraints.copy(
                minWidth = 0,
                minHeight = 0,
                maxHeight = Constraints.Infinity,
            ),
        )
        layout(
            width = constraints.constrainWidth(placeable.width),
            height = constraints.constrainHeight(placeable.height),
        ) {
            placeable.place(0, 0)
        }
    }
}

@Composable
private fun WrappedPatchText(
    value: String,
    textStyle: TextStyle,
    color: Color = Color.Unspecified,
) {
    WrappedPatchLines(
        lines = listOf(
            StyledPatchLine(
                text = value,
                style = SpanStyle(color = color, textStyle = textStyle),
            ),
        ),
    )
}

@Composable
private fun WrappedPatchLines(lines: List<StyledPatchLine>) {
    SubcomposeLayout(modifier = Modifier.fillMaxWidth()) { constraints ->
        check(constraints.hasBoundedWidth) {
            "Patch text must be measured with a finite maximum width."
        }
        val width = constraints.maxWidth.coerceAtLeast(1)
        val text = lines.toWrappedAnnotatedString(width)
        val placeable = subcompose(WrappedPatchLinesSlot(text)) {
            Text(text)
        }.single().measure(
            constraints.copy(
                minWidth = 0,
                minHeight = 0,
                maxHeight = Constraints.Infinity,
            ),
        )
        layout(
            width = constraints.constrainWidth(placeable.width),
            height = constraints.constrainHeight(placeable.height),
        ) {
            placeable.place(0, 0)
        }
    }
}

private data class StyledPatchLine(
    val text: String,
    val style: SpanStyle,
)

private data class PatchShowMoreSlot(
    val remainingLineCount: Int,
)

private fun List<StyledPatchLine>.toWrappedAnnotatedString(width: Int): AnnotatedString =
    buildAnnotatedString {
        var hasWrittenLine = false
        this@toWrappedAnnotatedString.forEach { sourceLine ->
            sourceLine.text.wrapPatchText(
                width = width,
                continuationPrefix = "  ",
            ).forEach { wrappedLine ->
                if (hasWrittenLine) {
                    append('\n')
                }
                withStyle(sourceLine.style) {
                    append(wrappedLine)
                }
                hasWrittenLine = true
            }
        }
    }

private fun String.wrapPatchText(
    width: Int,
    continuationPrefix: String,
): List<String> {
    require(width > 0)
    if (isEmpty()) return listOf("")

    return lineSequence().flatMap { hardLine ->
        hardLine.wrapPatchHardLine(
            width = width,
            continuationPrefix = continuationPrefix,
        ).asSequence()
    }.toList()
}

internal fun String.wrapPatchHardLine(
    width: Int,
    continuationPrefix: String,
): List<String> {
    if (isEmpty()) return listOf("")

    return buildList {
        var remaining = this@wrapPatchHardLine
        var firstLine = true
        while (remaining.isNotEmpty()) {
            var prefix = if (firstLine) "" else continuationPrefix
            var availableWidth = width - prefix.terminalCellWidth()
            var fitting = if (availableWidth > 0) {
                remaining.takeFirstFittingTerminalWidth(availableWidth)
            } else {
                ""
            }
            if (fitting.isEmpty() && prefix.isNotEmpty()) {
                prefix = ""
                availableWidth = width
                fitting = remaining.takeFirstFittingTerminalWidth(availableWidth)
            }

            if (fitting.isEmpty()) {
                val unfittableGrapheme = remaining.takeFirstFittingTerminalWidth(
                    maximumWidth = maxOf(width, 2),
                )
                check(unfittableGrapheme.isNotEmpty()) {
                    "Unable to consume the first terminal grapheme."
                }
                add(prefix + "?")
                remaining = remaining.removePrefix(unfittableGrapheme)
            } else {
                add(prefix + fitting)
                remaining = remaining.removePrefix(fitting)
            }
            firstLine = false
        }
    }
}

private fun PatchPresentationLineKind.color(): Color = when (this) {
    PatchPresentationLineKind.Addition -> Color.Green
    PatchPresentationLineKind.Removal,
    PatchPresentationLineKind.Failure,
    -> Color.Red

    PatchPresentationLineKind.Metadata,
    PatchPresentationLineKind.File,
    PatchPresentationLineKind.Context,
    -> Color.Unspecified
}

private fun PatchPresentationLineKind.textStyle(): TextStyle = when (this) {
    PatchPresentationLineKind.File -> TextStyle.Bold
    PatchPresentationLineKind.Metadata,
    PatchPresentationLineKind.Context,
    -> TextStyle.Dim

    PatchPresentationLineKind.Addition,
    PatchPresentationLineKind.Removal,
    PatchPresentationLineKind.Failure,
    -> TextStyle.Unspecified
}

private data class WrappedPatchLinesSlot(val text: AnnotatedString)

private data class PatchBodySlot(val text: AnnotatedString)
