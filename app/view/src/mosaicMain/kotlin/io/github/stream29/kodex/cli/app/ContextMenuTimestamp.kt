package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.cli.components.formatPopupTimestamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.datetime.TimeZone
import kotlin.coroutines.coroutineContext
import kotlin.time.Instant

/**
 * One optional field snapshot owned by this exact menu opening.
 * @param read null means there is no exact timestamp to display.
 * @return null while no successfully read and formatted timestamp is available.
 */
@Composable
internal fun rememberMenuTimestamp(request: Any, read: suspend () -> Instant?): String? {
    var value by remember(request) { mutableStateOf<String?>(null) }
    LaunchedEffect(request) {
        val loaded = try {
            read()?.let { formatPopupTimestamp(it, TimeZone.currentSystemDefault()) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }
        coroutineContext.ensureActive()
        value = loaded
    }
    return value
}

/** @param value null omits this information item entirely. */
@Composable
internal fun TimestampInformation(label: String, value: String?) {
    if (value == null) return
    Text("$label: $value")
}
