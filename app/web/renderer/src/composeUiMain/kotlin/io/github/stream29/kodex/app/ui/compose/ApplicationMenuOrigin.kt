package io.github.stream29.kodex.app.ui.compose

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.stream29.kodex.app.sessiontabbar.contract.SessionTabMenuPlacement
import io.github.stream29.kodex.cli.app.ApplicationHistoryMenuPlacement

/** The live attachment retires a captured menu when its exact widget leaves layout. */
internal class ComposeMenuOrigin {
    var bounds: Rect? by mutableStateOf(null)
    val isPlaced: Boolean get() = bounds?.let { it.width > 0 && it.height > 0 } == true
}

/** Freezes the relative activation point; anchor updates and liveness follow the exact original widget. */
internal data class ComposeSessionTabPlacement(
    val origin: ComposeMenuOrigin, val bounds: Rect, val point: Offset?,
) : SessionTabMenuPlacement

internal data class ComposeHistoryMenuPlacement(
    val origin: ComposeMenuOrigin, val bounds: Rect, val point: Offset?,
) : ApplicationHistoryMenuPlacement
