package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun rememberInitialFocusModifier(autoFocus: Boolean, enabled: Boolean): Modifier {
    val requester = remember { FocusRequester() }
    val reveal = remember { BringIntoViewRequester() }
    val admitted by rememberUpdatedState(enabled)
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(autoFocus, enabled, placed) {
        if (autoFocus && admitted && placed) {
            requester.requestFocus()
            reveal.bringIntoView()
        }
    }
    return Modifier.focusRequester(requester).bringIntoViewRequester(reveal)
        .onGloballyPositioned { placed = it.isAttached && it.size.width > 0 && it.size.height > 0 }
}

@Composable
internal fun CompactTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    val admitted by rememberUpdatedState(enabled)
    val invoke by rememberUpdatedState(onClick)
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 32.dp) {
        TextButton(
            { if (admitted) invoke() },
            modifier.defaultMinSize(minWidth = 1.dp, minHeight = 32.dp),
            enabled = enabled,
            shape = RoundedCornerShape(4.dp),
            colors = colors,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            content = content,
        )
    }
}

@Composable
internal fun CompactOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val admitted by rememberUpdatedState(enabled)
    val invoke by rememberUpdatedState(onClick)
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 32.dp) {
        OutlinedButton(
            { if (admitted) invoke() },
            modifier.defaultMinSize(minWidth = 1.dp, minHeight = 32.dp),
            enabled = enabled,
            shape = RoundedCornerShape(4.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            content = content,
        )
    }
}

@Composable
internal fun CompactChoiceMenuItem(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val admitted by rememberUpdatedState(enabled)
    val invoke by rememberUpdatedState(onClick)
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().defaultMinSize(minHeight = 32.dp)
            .onFocusChanged { focused = it.isFocused }
            .border(1.dp, if (focused && enabled) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(4.dp))
            .semantics { this.selected = selected }
            .clickable(enabled = enabled, role = Role.Button) { if (admitted) invoke() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f),
        )
    }
}
