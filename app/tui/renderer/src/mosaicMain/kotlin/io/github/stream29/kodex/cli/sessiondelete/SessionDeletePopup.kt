package io.github.stream29.kodex.cli.sessiondelete

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.BoxScope
import io.github.stream29.kodex.app.sessiondelete.SessionDeleteContent
import io.github.stream29.kodex.app.sessiondelete.contract.SessionDeleteViewModel
import io.github.stream29.kodex.cli.components.TuiDialog
import io.github.stream29.kodex.cli.components.TuiTheme

@Composable
public actual fun BoxScope.SessionDeletePopup(
    viewModel: SessionDeleteViewModel,
    onDismissRequest: () -> Unit,
    onResult: (Boolean) -> Unit,
) {
    SessionDeleteContent(viewModel, onDismissRequest, onResult, frame = { content ->
        TuiDialog(
            onDismissRequest = onDismissRequest,
            modifier = Modifier.width(52).background(TuiTheme.colorScheme.surfaceContainer),
        ) { content() }
    })
}
