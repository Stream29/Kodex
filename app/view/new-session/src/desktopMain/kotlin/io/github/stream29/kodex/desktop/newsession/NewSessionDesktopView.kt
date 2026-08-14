package io.github.stream29.kodex.desktop.newsession

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import io.github.stream29.kodex.desktop.components.DesktopComposer
import io.github.stream29.kodex.desktop.components.DesktopComposerSubmitKey

/** Blank history area and composer used by one process-local New Session tab. */
@Composable
public fun NewSessionDesktopView(
    viewModel: NewSessionViewModel,
    submitKey: DesktopComposerSubmitKey,
    onMaterialize: () -> Unit,
    modifier: Modifier = Modifier,
): Unit {
    val composer by viewModel.composer.state.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp),
            contentAlignment = Alignment.TopStart,
        ) {
            Text(
                text = "Enter a prompt to create a session",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        DesktopComposer(
            text = composer.text,
            cursorOffset = composer.cursorOffset,
            submitKey = submitKey,
            onValueChange = viewModel.composer::update,
            onSubmit = onMaterialize,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
