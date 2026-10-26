package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.history.contract.HistoryStreamingItem

@Composable
internal fun HistoryStreamingItem.renderTransientTail(onContentChange: () -> Unit = {}) {
    HistoryStreamingContent(this, MosaicHistoryContentRenderer(), onContentChange)
}
