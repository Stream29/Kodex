package io.github.stream29.kodex.cli.newsession

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.openai.KodexAgentSettings

/** Renders settings for one virtual New-session tab. */
@Composable
public fun NewSessionDefaults(settings: KodexAgentSettings) {
    Column {
        Text("New session settings")
        Text("Model: ${settings.model}")
        Text("Reasoning: ${settings.reasoning.effort}")
        Text("Service tier: ${settings.serviceTier}")
    }
}
