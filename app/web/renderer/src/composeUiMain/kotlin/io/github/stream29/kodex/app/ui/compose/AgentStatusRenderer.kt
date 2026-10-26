package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationViewModel
import io.github.stream29.kodex.cli.app.AgentHistorySeparatorRenderer
import io.github.stream29.kodex.cli.app.AgentStatusRenderer
import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeConfigurationContent
import kotlinx.io.files.Path
import androidx.compose.material3.Text as MaterialText

@Composable
public actual fun rememberComposeAgentStatusRenderer(): AgentStatusRenderer =
    remember { ComposeAgentStatusRenderer() }

@Composable
public actual fun rememberComposeAgentHistorySeparatorRenderer(): AgentHistorySeparatorRenderer =
    remember { ComposeAgentHistorySeparatorRenderer() }

private class ComposeAgentStatusRenderer : AgentStatusRenderer {
    @Composable
    override fun Frame(regular: @Composable () -> Unit, settings: @Composable () -> Unit) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) { regular() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { settings() }
        }
    }

    @Composable
    override fun Text(value: String) { MaterialText(value) }

    @Composable
    override fun Action(label: String, enabled: Boolean, onClick: () -> Unit) {
        OutlinedButton(onClick = onClick, enabled = enabled) { MaterialText(label) }
    }

    @Composable
    override fun Configuration(viewModel: RuntimeConfigurationViewModel) {
        RuntimeConfigurationContent(viewModel, rememberComposeRuntimeConfigurationRenderer())
    }

    @Composable
    override fun Directory(directory: Path, onBrowse: () -> Unit) {
        TextButton(onClick = onBrowse, enabled = LocalGraphicalHostCapabilities.current.directoryBrowsing,
            modifier = Modifier.semantics { contentDescription = "Working directory: $directory" }) {
            MaterialText(directory.toString(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    @Composable
    override fun Settings(onOpen: () -> Unit) {
        TextButton(onClick = onOpen) { MaterialText("Settings") }
    }
}

private class ComposeAgentHistorySeparatorRenderer : AgentHistorySeparatorRenderer {
    @Composable
    override fun Separator(label: String?, showScrollToLatest: Boolean, onScrollToLatest: () -> Unit) {
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider()
            if (label != null || showScrollToLatest) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (label != null) MaterialText(label, modifier = Modifier.weight(1f))
                    else Spacer(Modifier.weight(1f))
                    if (showScrollToLatest) TextButton(onClick = onScrollToLatest) { MaterialText("Latest") }
                }
            }
        }
    }
}
