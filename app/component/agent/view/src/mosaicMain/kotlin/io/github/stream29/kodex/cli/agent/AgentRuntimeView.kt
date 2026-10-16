package io.github.stream29.kodex.cli.agent

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.rpc.models.AgentStateValue

@Composable
public fun AgentRuntimeStatus(state: AgentStateValue) {
    Text(state.label())
}
