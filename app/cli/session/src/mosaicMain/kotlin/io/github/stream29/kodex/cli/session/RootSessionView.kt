package io.github.stream29.kodex.cli.session

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text

/** Renders a root session's discovered Agent tree from typed VM state. */
@Composable
public fun RootSessionTree(state: RootSessionViewState) {
    Column {
        state.renderTreeLines().forEach { line -> Text(line) }
    }
}
