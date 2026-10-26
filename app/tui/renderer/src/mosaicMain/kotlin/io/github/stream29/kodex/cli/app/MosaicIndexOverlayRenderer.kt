package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.jakewharton.mosaic.ui.BoxScope
import io.github.stream29.kodex.cli.historyindex.*

public actual fun mosaicIndexHover(request: HistoryIndexInteractionRequest): HistoryIndexInteraction =
    MosaicHistoryIndexOrigin(request)

public actual fun mosaicIndexMenu(request: HistoryIndexMenuRequest): HistoryIndexMenuOpening =
    HistoryIndexMenuOpening(MosaicHistoryIndexOrigin(request.target, request.clickPosition))

@Composable
public actual fun BoxScope.rememberMosaicIndexOverlayRenderer(columns: Int, rows: Int): HistoryIndexRenderer =
    remember(this, columns, rows) { MosaicHistoryIndexRenderer(columns, rows, popupScope = this) }
