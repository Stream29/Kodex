package io.github.stream29.kodex.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.app.ui.compose.rememberInitialFocusModifier
import io.github.stream29.kodex.app.ui.compose.CompactTextButton

@Composable
internal fun BrowserUiProse(text: String, role: UiTextRole) {
    SelectionContainer { UiText(text, role) }
}

@Composable
internal fun BrowserTextInputFrame(label: String, content: @Composable () -> Unit): Unit = content()

@Composable
internal fun BrowserUiColumn(content: @Composable () -> Unit): Unit =
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }

@Composable
internal fun BrowserUiActions(content: @Composable () -> Unit): Unit =
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp, androidx.compose.ui.Alignment.End),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        content()
    }

@Composable
internal fun BrowserUiText(text: String, role: UiTextRole, maximumLines: Int) {
    require(maximumLines > 0)
    Text(
        text,
        maxLines = maximumLines,
        overflow = TextOverflow.Ellipsis,
        style = when (role) {
            UiTextRole.Title -> MaterialTheme.typography.titleMedium
            UiTextRole.Supporting -> MaterialTheme.typography.bodySmall
            else -> MaterialTheme.typography.bodyMedium
        },
        color = when (role) {
            UiTextRole.Error -> MaterialTheme.colorScheme.error
            UiTextRole.Supporting -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSurface
        },
    )
}

@Composable
internal fun BrowserUiAction(
    label: String, onClick: () -> Unit, enabled: Boolean, autoFocus: Boolean, role: UiActionRole,
) {
    val admitted by rememberUpdatedState(enabled)
    val invoke by rememberUpdatedState(onClick)
    val click = { if (admitted) invoke() }
    val modifier = rememberInitialFocusModifier(autoFocus, enabled)
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 32.dp) {
      when (role) {
        UiActionRole.Primary -> Button(
            click, modifier.defaultMinSize(minHeight = 36.dp), enabled = enabled,
            shape = RoundedCornerShape(4.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) { Text(label) }
        UiActionRole.Secondary -> FilledTonalButton(
            click, modifier.defaultMinSize(minHeight = 36.dp), enabled = enabled,
            shape = RoundedCornerShape(4.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) { Text(label) }
        UiActionRole.Normal -> CompactTextButton(click, modifier, enabled = enabled) { Text(label) }
        UiActionRole.Destructive -> CompactTextButton(
            click, modifier, enabled = enabled,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text(label) }
      }
    }
}
