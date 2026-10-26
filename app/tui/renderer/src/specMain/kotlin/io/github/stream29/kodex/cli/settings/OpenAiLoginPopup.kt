package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.ui.BoxScope
import io.github.stream29.kodex.app.settings.contract.OpenAiLoginViewModel
import io.github.stream29.kodex.utils.externalurl.OpenExternalUrlResult
import io.github.stream29.kodex.utils.externalurl.openExternalUrl

/**
 * Mosaic renderer for the component spec, without Settings or auth-store access.
 *
 * [openUrl] reports only launcher acceptance and must return a failed result
 * for launch failures (cancellation still propagates). Each output is checked
 * against the current attempt before launching; the exact id is reported back.
 * Dismissal cancels the attempt and notifies the parent, disposal closes the
 * child, and Completed remains visible until explicitly closed.
 */
@Composable
public expect fun BoxScope.OpenAiLoginPopup(
    viewModel: OpenAiLoginViewModel,
    onDismissRequest: () -> Unit,
    openUrl: suspend (String) -> OpenExternalUrlResult = ::openExternalUrl,
): Unit
