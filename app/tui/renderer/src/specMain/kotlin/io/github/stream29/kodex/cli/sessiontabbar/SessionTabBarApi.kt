package io.github.stream29.kodex.cli.sessiontabbar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import com.jakewharton.mosaic.ui.unit.IntOffset
import io.github.stream29.kodex.app.sessiontabbar.contract.SessionTabBarRenderer
import io.github.stream29.kodex.app.sessiontabbar.contract.SessionTabMenuPlacement
import io.github.stream29.kodex.cli.components.TuiPopupAnchor

public data class MosaicSessionTabMenuPlacement(
    public val anchor: TuiPopupAnchor,
    public val clickPosition: IntOffset?,
) : SessionTabMenuPlacement

@Composable
public expect fun rememberMosaicSessionTabBarRenderer(
    columns: Int,
    runningIndicatorFrame: State<String>,
): SessionTabBarRenderer
