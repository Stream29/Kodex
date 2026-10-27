package io.github.stream29.kodex.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.heightIn
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.SubcomposeLayout
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.cli.components.ActionTextInput
import io.github.stream29.kodex.cli.components.EllipsizedText
import io.github.stream29.kodex.cli.components.LocalTextInputFocusRequester
import io.github.stream29.kodex.cli.components.TextInputLayout
import io.github.stream29.kodex.cli.components.TextInputState
import io.github.stream29.kodex.cli.components.TextInputValue
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.TuiDialogActionRow
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.cli.settings.SubmitKey
import io.github.stream29.kodex.cli.history.WrappedHistoryText

internal object MosaicUiRenderer : UiRenderer {
    @Composable
    override fun UiProse(text: String, role: UiTextRole) {
        WrappedHistoryText(
            text,
            textStyle = when (role) {
                UiTextRole.Title -> TuiTheme.typography.title
                UiTextRole.Supporting -> TuiTheme.typography.supporting
                else -> TuiTheme.typography.body
            },
            color = if (role == UiTextRole.Error) TuiTheme.colorScheme.error else TuiTheme.colorScheme.onSurface,
        )
    }

    @Composable
    override fun UiTheme(content: @Composable () -> Unit): Unit = TuiTheme(content = content)

    @Composable
    override fun UiColumn(content: @Composable () -> Unit): Unit = Column { content() }

    @Composable
    override fun UiActions(content: @Composable () -> Unit): Unit =
        TuiDialogActionRow(modifier = Modifier.fillMaxWidth()) { content() }

    @Composable
    override fun TextInputFrame(label: String, content: @Composable () -> Unit) {
        Column {
            UiText(label, UiTextRole.Supporting, Int.MAX_VALUE)
            content()
        }
    }

    @Composable
    override fun UiText(text: String, role: UiTextRole, maximumLines: Int) {
        require(maximumLines > 0)
        val color = if (role == UiTextRole.Error) TuiTheme.colorScheme.error else TuiTheme.colorScheme.onSurface
        val style = when (role) {
            UiTextRole.Title -> TuiTheme.typography.title
            UiTextRole.Supporting -> TuiTheme.typography.supporting
            else -> TuiTheme.typography.body
        }
        if (maximumLines == 1) {
            EllipsizedText(text.lineSequence().first(), color = color, textStyle = style)
        } else {
            Text(text, modifier = Modifier.heightIn(max = maximumLines), color = color, textStyle = style)
        }
    }

    @Composable
    override fun UiAction(
        label: String, onClick: () -> Unit, enabled: Boolean, autoFocus: Boolean, role: UiActionRole,
    ) {
        TuiButton(
            label, onClick = onClick, enabled = enabled, autoFocus = autoFocus,
            color = if (role == UiActionRole.Destructive) TuiTheme.colorScheme.error else TuiTheme.colorScheme.onSurface,
        )
    }

    @Composable
    override fun UiEditor(
        text: String,
        cursorOffset: Int,
        onChange: (String, Int) -> Long?,
        onSubmit: (text: String, cursorOffset: Int) -> Unit,
        label: String,
        enabled: Boolean,
        readOnly: Boolean,
        autoFocus: Boolean,
        multiline: Boolean,
        maximumLines: Int,
        submitKey: UiSubmitKey,
        revision: Long?,
    ) {
        require(maximumLines > 0)
        require(cursorOffset in 0..text.length)
        val input = remember { TextInputState(TextInputValue(text, cursorOffset)) }
        var acceptedRevision by remember { mutableStateOf(revision) }
        LaunchedEffect(text, cursorOffset, revision) {
            val accepted = acceptedRevision
            if (revision != null && accepted != null && revision < accepted) return@LaunchedEffect
            if (input.value.text != text || input.value.cursorOffset != cursorOffset) {
                input.reset(TextInputValue(text, cursorOffset))
            }
        }
        val terminalColumns = LocalTerminalState.current.size.columns.coerceAtLeast(1)
        val ownFocusRequester = remember { FocusRequester() }
        val focusRequester = LocalTextInputFocusRequester.current ?: ownFocusRequester
        val canFocus by rememberUpdatedState(enabled)
        BindUiEditorFocus {
            if (!canFocus) false else try { focusRequester.requestFocus() } catch (_: IllegalStateException) { false }
        }
        val contentCache = remember(
            input, label, multiline, maximumLines, enabled, readOnly, autoFocus,
            focusRequester, submitKey, onSubmit, onChange,
        ) {
            UiEditorContentCache { width, maximumHeight ->
                {
                    val full = TextInputLayout.create(
                        input.value,
                        width,
                        firstLinePrefix = if (label.isEmpty()) "> " else "",
                        continuationLinePrefix = if (label.isEmpty()) "  " else "",
                        softWrap = multiline,
                    )
                    ActionTextInput(
                        state = input,
                        layout = full.withViewportRows(minOf(
                            full.rowCount, if (multiline) maximumLines else 1, maximumHeight.coerceAtLeast(1),
                        )),
                        enabled = enabled,
                        readOnly = readOnly,
                        autoFocus = autoFocus,
                        focusRequester = focusRequester,
                        multiline = multiline,
                        submitKey = if (submitKey == UiSubmitKey.Enter) SubmitKey.Enter else SubmitKey.CtrlEnter,
                        onSubmit = { onSubmit(input.value.text, input.value.cursorOffset) },
                        onValueChanged = {
                            onChange(it.text, it.cursorOffset)?.let { value -> acceptedRevision = value }
                        },
                    )
                    LaunchedEffect(autoFocus, enabled, focusRequester) {
                        if (autoFocus && enabled) {
                            // Let the departing tab release focus after this subcomposed editor attaches.
                            withFrameNanos { }
                            focusRequester.requestFocus()
                        }
                    }
                }
            }
        }
        Column {
            if (label.isNotEmpty()) UiText(label, UiTextRole.Supporting)
            SubcomposeLayout(modifier = Modifier.fillMaxWidth()) { constraints ->
                val width = minOf(constraints.maxWidth, terminalColumns).coerceAtLeast(1)
                val nodes = subcompose(
                    Unit,
                    content = contentCache.contentFor(width, constraints.maxHeight),
                ).map { it.measure(constraints) }
                layout(nodes.maxOfOrNull { it.width } ?: 0, nodes.maxOfOrNull { it.height } ?: 0) {
                    nodes.forEach { it.place(0, 0) }
                }
            }
        }
    }
}

// Keep one immutable constraint capture: a fresh measure-time lambda recomposes the input,
// replaces its measure policy and requests the next root layout while this one is running.
private class UiEditorContentCache(
    private val createContent: (Int, Int) -> (@Composable () -> Unit),
) {
    private var width = -1
    private var maximumHeight = -1
    private var content: (@Composable () -> Unit)? = null

    fun contentFor(width: Int, maximumHeight: Int): @Composable () -> Unit {
        val cached = content
        if (cached != null && this.width == width && this.maximumHeight == maximumHeight) return cached
        return createContent(width, maximumHeight).also {
            this.width = width
            this.maximumHeight = maximumHeight
            content = it
        }
    }
}
