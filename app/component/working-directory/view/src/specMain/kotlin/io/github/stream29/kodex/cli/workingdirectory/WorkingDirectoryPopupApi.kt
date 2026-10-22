package io.github.stream29.kodex.cli.workingdirectory

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.ui.BoxScope
import io.github.stream29.kodex.app.workingdirectory.contract.WorkingDirectoryViewModel

/**
 * One owning component over a borrowed browser renderer. Selection starts from
 * the browser's confirmed output; Settings handoff can finish synchronously,
 * whereas an Application operation may suspend. Only a still-present instance
 * reports normal completion to its own host.
 */
@Composable
public expect fun BoxScope.WorkingDirectoryPopup(
    viewModel: WorkingDirectoryViewModel,
    onDismissRequest: () -> Unit,
    onSelected: () -> Unit,
): Unit
