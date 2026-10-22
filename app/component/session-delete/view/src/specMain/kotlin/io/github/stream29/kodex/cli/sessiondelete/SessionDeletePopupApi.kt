package io.github.stream29.kodex.cli.sessiondelete

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.ui.BoxScope
import io.github.stream29.kodex.app.sessiondelete.contract.SessionDeleteViewModel

/**
 * Captured-target confirmation. Reports true and false unchanged to [onResult];
 * only the host decides whether to dismiss. Failure/cancellation does not invoke
 * that callback. Cancel performs no operation. Disposal closes only the child.
 */
@Composable
public expect fun BoxScope.SessionDeletePopup(
    viewModel: SessionDeleteViewModel,
    onDismissRequest: () -> Unit,
    onResult: (Boolean) -> Unit,
): Unit
