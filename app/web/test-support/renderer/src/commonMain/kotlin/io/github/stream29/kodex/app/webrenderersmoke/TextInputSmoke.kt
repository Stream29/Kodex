package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.*
import io.github.stream29.kodex.app.ui.*

/** Backend-free fields for compiled DOM, focus and overlay checks. */
@Composable
internal fun TextInputSmoke(onBack: () -> Unit, dialog: @Composable (onClose: () -> Unit) -> Unit) {
    var answer by remember { mutableStateOf("Editable answer") }
    var feedback by remember { mutableStateOf("选区 😀 <literal>") }
    var enabled by remember { mutableStateOf(true) }
    var rejectNextAnswer by remember { mutableStateOf(false) }
    var overlay by remember { mutableStateOf(false) }
    var overlayText by remember { mutableStateOf("") }
    var showDialog by remember { mutableStateOf(false) }
    var submissions by remember { mutableIntStateOf(0) }
    val answerFocus = rememberUiEditorFocusHandle()
    val feedbackFocus = rememberUiEditorFocusHandle()
    UiColumn {
        UiText("Native form fields — isolated fixture", UiTextRole.Title)
        UiText("No backend or real Home. This boundary probe is not a real dialog.", UiTextRole.Supporting)
        UiAction("Back from form fixture", onClick = onBack)
        UiAction("Focus fixture answer", onClick = { answerFocus.requestFocus() })
        UiAction("Focus fixture feedback", onClick = { feedbackFocus.requestFocus() })
        UiAction("Toggle fixture fields enabled", onClick = { enabled = !enabled })
        UiAction("Reject next fixture answer edit", onClick = { rejectNextAnswer = true })
        UiAction("Show fixture overlay boundary", onClick = { overlay = true })
        UiAction("Open fixture Hook dialog", onClick = { showDialog = true })
        WithUiEditorFocus(answerFocus) {
            UiTextInput(
                answer, {
                    if (rejectNextAnswer) {
                        rejectNextAnswer = false
                        enabled = false
                    } else answer = it
                }, "Fixture answer", enabled = enabled,
                onSubmit = { feedbackFocus.requestFocus() },
            )
        }
        WithUiEditorFocus(feedbackFocus) {
            UiTextInput(
                feedback, { feedback = it }, "Fixture feedback", enabled = enabled,
                onSubmit = { submissions++ },
            )
        }
        UiAction("Submit fixture fields", onClick = { submissions++ }, enabled = enabled)
        UiText("Field submissions=$submissions; enabled=$enabled")
        UiText("Answer UTF-16 length=${answer.length}; feedback UTF-16 length=${feedback.length}")
        UiText("Answer owner units: ${answer.map { it.code }.joinToString(",")}")
        if (overlay) UiEditorOverlay {
            UiColumn {
                UiTextInput(overlayText, { overlayText = it }, "Fixture overlay field", autoFocus = true)
                UiAction("Close fixture overlay boundary", onClick = { overlay = false })
            }
        }
        if (showDialog) dialog { showDialog = false }
    }
}
