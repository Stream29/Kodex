package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import io.github.stream29.kodex.app.ui.UiTextInputRenderer
import io.github.stream29.kodex.app.ui.BindUiEditorFocus

/** Raster leaf only: keeps shared form callbacks testable, not evidence for native input or focus. */
internal object RasterTextInput : UiTextInputRenderer {
    @Composable
    override fun Field(
        text: String, onChange: (String) -> Unit, label: String,
        enabled: Boolean, autoFocus: Boolean, onSubmit: () -> Unit,
    ) {
        val focus = remember { FocusRequester() }
        BindUiEditorFocus {
            if (!enabled) false else try { focus.requestFocus() } catch (_: IllegalStateException) { false }
        }
        OutlinedTextField(
            text, onChange, label = { Text(label) }, enabled = enabled, singleLine = true,
            modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = label },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (enabled) onSubmit() }),
        )
        LaunchedEffect(autoFocus, enabled) { if (autoFocus && enabled) focus.requestFocus() }
    }
}
