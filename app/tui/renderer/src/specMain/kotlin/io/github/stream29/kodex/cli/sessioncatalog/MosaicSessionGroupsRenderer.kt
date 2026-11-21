package io.github.stream29.kodex.cli.sessioncatalog

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.BoxScope

/** Mosaic-only host integration for the shared Sessions panel. */
public interface MosaicSessionGroupsRenderer : SessionGroupsRenderer {
    /** Mount once as a direct child of the root TuiPopupHost, after its persistent content. */
    @Composable
    public fun Overlays()
}

/** [columns] is the panel's actual content width. Mount the panel in a vertically bounded slot. */
@Composable
public expect fun BoxScope.rememberMosaicSessionGroupsRenderer(
    columns: Int,
    modifier: Modifier = Modifier,
): MosaicSessionGroupsRenderer
