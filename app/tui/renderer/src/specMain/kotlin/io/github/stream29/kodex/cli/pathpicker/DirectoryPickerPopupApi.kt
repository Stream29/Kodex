package io.github.stream29.kodex.cli.pathpicker

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.ui.BoxScope
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerViewModel
import kotlinx.io.files.Path

/**
 * Modal directory browser that reports an explicitly confirmed, absolute directory to its caller.
 *
 * It exposes no caller-specific persistence behavior. Cancelling and outside dismissal invoke
 * [onDismissRequest]; Escape clears an active filter before invoking it.
 */
@Composable
public expect fun BoxScope.DirectoryPickerPopup(
    viewModel: DirectoryPickerViewModel,
    onDismissRequest: () -> Unit,
    onDirectorySelected: (Path) -> Unit,
): Unit

/**
 * Explicit disposal-ownership variant. Set [closeOnDispose] false when an
 * enclosing component owns/closes the child. Keep this choice fixed for the
 * lifetime of one child; the three-argument overload retains standalone ownership.
 */
@Composable
public expect fun BoxScope.DirectoryPickerPopup(
    viewModel: DirectoryPickerViewModel,
    onDismissRequest: () -> Unit,
    onDirectorySelected: (Path) -> Unit,
    closeOnDispose: Boolean,
): Unit
