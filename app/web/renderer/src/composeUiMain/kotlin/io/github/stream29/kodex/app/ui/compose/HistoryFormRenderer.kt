package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.cli.history.HistoryFormValueKind
import io.github.stream29.kodex.cli.history.HistoryFormRenderer
import io.github.stream29.kodex.cli.history.RequestUserInputHistoryRowModel
import io.github.stream29.kodex.cli.history.RequestUserInputHistoryRowRole

@Composable
public actual fun rememberComposeHistoryFormRenderer(): HistoryFormRenderer = ComposeHistoryFormRenderer

private object ComposeHistoryFormRenderer : HistoryFormRenderer {
    @Composable override fun Frame(content: @Composable () -> Unit) {
        Column(Modifier.fillMaxWidth()) { content() }
    }
    @Composable override fun Row(row: RequestUserInputHistoryRowModel) {
        val text: @Composable () -> Unit = {
            Text(
                row.value,
                fontWeight = if (row.role == RequestUserInputHistoryRowRole.Header) FontWeight.Bold else FontWeight.Normal,
                color = when (row.role) {
                    RequestUserInputHistoryRowRole.Error -> MaterialTheme.colorScheme.error
                    RequestUserInputHistoryRowRole.Supporting -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
        SelectionContainer {
            when (row.kind) {
                HistoryFormValueKind.SelectedOption -> Surface(
                    modifier = Modifier.padding(vertical = 2.dp).semantics { stateDescription = "Selected" },
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) { androidx.compose.foundation.layout.Box(Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) { text() } }
                HistoryFormValueKind.FreeText, HistoryFormValueKind.OptionDescription ->
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(start = 12.dp)) { text() }
                HistoryFormValueKind.Plain -> androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth()) { text() }
            }
        }
    }
}

/** History-only answer rows; Index and suggestion forms retain their existing renderer. */
internal object ComposeAnsweredQuestionHistoryFormRenderer : HistoryFormRenderer {
    @Composable override fun Frame(content: @Composable () -> Unit) {
        ComposeHistoryFormRenderer.Frame(content)
    }

    @Composable override fun Row(row: RequestUserInputHistoryRowModel) {
        if (row.kind != HistoryFormValueKind.SelectedOption) {
            ComposeHistoryFormRenderer.Row(row)
            return
        }
        Surface(
            modifier = Modifier.padding(vertical = 2.dp).semantics(mergeDescendants = true) {
                stateDescription = "Selected"
            },
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) {
                Box(Modifier.width(20.dp * LocalDensity.current.fontScale)) {
                    Text(">", Modifier.clearAndSetSemantics { testTag = "history-answer-mark" })
                }
                SelectionContainer {
                    Text(row.value, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
