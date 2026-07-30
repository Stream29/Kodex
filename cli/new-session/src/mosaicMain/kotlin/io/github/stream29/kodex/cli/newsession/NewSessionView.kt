package io.github.stream29.kodex.cli.newsession

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text

/** Renders settings for one virtual New-session tab. */
@Composable
public fun NewSessionDefaults(state: NewSessionViewState) {
    Column {
        Text("New session settings")
        Text("Model: ${state.settings.model}")
        Text("Reasoning: ${state.settings.reasoningEffort}")
        Text("Service tier: ${state.settings.serviceTier}")
        Text("Mode: ${state.settings.mode}")
        if (state.creating) Text("Creating session...")
        state.failureMessage?.let { message -> Text("Create failed: $message") }
    }
}
