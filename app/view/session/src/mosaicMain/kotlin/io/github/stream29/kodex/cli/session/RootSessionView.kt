package io.github.stream29.kodex.cli.session

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.app.agent.contract.AgentAddress
import io.github.stream29.kodex.app.session.contract.PersistedSessionTopologyState

/** Renders a root session's discovered Agent tree from typed VM state. */
@Composable
public fun RootSessionTree(
    topology: PersistedSessionTopologyState,
    selectedAddress: AgentAddress,
) {
    Column {
        topology.renderTreeLines(selectedAddress).forEach { line -> Text(line) }
    }
}
