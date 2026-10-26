package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.ui.BoxScope

@Composable
public expect fun BoxScope.rememberMosaicApplicationPopupRenderer(): ApplicationPopupRenderer
