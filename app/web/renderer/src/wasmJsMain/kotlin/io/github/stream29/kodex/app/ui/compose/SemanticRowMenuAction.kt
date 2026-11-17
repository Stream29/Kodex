package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

internal val LocalRowMenuFallback = staticCompositionLocalOf<FocusRequester?> { null }
internal val LocalSidebarFocusRestoreAllowed = staticCompositionLocalOf<() -> Boolean> { { true } }

internal class RowMenuFocusRestoration(private val scope: CoroutineScope) {
    private var active: Any? = null

    @Composable
    fun Track(opening: Any, fallback: FocusRequester?, mayRestore: () -> Boolean = { true }, isValid: () -> Boolean) {
        val valid = rememberUpdatedState(isValid)
        val allowed = rememberUpdatedState(mayRestore)
        DisposableEffect(opening, fallback) {
            active = opening
            onDispose {
                if (active === opening) {
                    active = null
                    if (fallback != null) scope.launch {
                        val started = TimeSource.Monotonic.markNow()
                        val frameArrived = withTimeoutOrNull(500.milliseconds) {
                            withFrameNanos { }
                            true
                        } == true
                        if (frameArrived && started.elapsedNow() <= 500.milliseconds &&
                            active == null && !valid.value() && allowed.value()
                        ) {
                            try { fallback.requestFocus() } catch (_: IllegalStateException) { }
                        }
                    }
                }
            }
        }
    }
}

/** A screen-reader menu action without a painted or pointer-clickable row button. */
@Composable
internal fun SemanticRowMenuAction(label: String, onOpen: () -> Boolean) {
    Box(Modifier.size(1.dp).semantics {
        contentDescription = label
        onClick(label = label, action = onOpen)
    })
}
