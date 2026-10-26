package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.stream29.kodex.app.sessiondelete.SessionDeleteContent
import io.github.stream29.kodex.app.sessiondelete.contract.SessionDeleteDependencies
import io.github.stream29.kodex.app.sessiondelete.createSessionDeleteViewModel
import io.github.stream29.kodex.app.sessionrename.SessionRenameContent
import io.github.stream29.kodex.app.sessionrename.contract.SessionRenameDependencies
import io.github.stream29.kodex.app.sessionrename.createSessionRenameViewModel
import io.github.stream29.kodex.app.ui.UiAction
import io.github.stream29.kodex.app.ui.UiColumn
import io.github.stream29.kodex.app.ui.UiEditor
import io.github.stream29.kodex.app.ui.UiText
import io.github.stream29.kodex.app.ui.UiTextRole
import io.github.stream29.kodex.app.ui.UiSubmitKey

@Composable
internal fun RendererSmoke(
    formDialog: @Composable (onClose: () -> Unit) -> Unit,
    rpcContent: @Composable (onBack: () -> Unit) -> Unit,
) {
    var rpcOnly by remember { mutableStateOf(false) }
    if (rpcOnly) {
        rpcContent { rpcOnly = false }
        return
    }
    var composerOnly by remember { mutableStateOf(false) }
    if (composerOnly) {
        ComposerSmoke(onBack = { composerOnly = false })
        return
    }
    var textInputOnly by remember { mutableStateOf(false) }
    if (textInputOnly) {
        TextInputSmoke(onBack = { textInputOnly = false }, dialog = formDialog)
        return
    }
    var text by remember { mutableStateOf("Draft") }
    var cursor by remember { mutableStateOf(text.length) }
    var submissions by remember { mutableStateOf(0) }
    var submitKey by remember { mutableStateOf(UiSubmitKey.Enter) }
    var deletions by remember { mutableStateOf(0) }
    var confirmation by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("No fixture result") }
    var renaming by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("captured") }
    var descriptors by remember { mutableStateOf("RPC descriptors not checked") }
    var serialization by remember { mutableStateOf("RPC serialization not checked") }
    UiColumn {
        UiText("Kodex renderer smoke — isolated fixture", UiTextRole.Title)
        UiText("No backend, credentials or real Session is used.", UiTextRole.Supporting)
        UiAction("Open shared Composer fixture", onClick = { composerOnly = true })
        UiAction("Open native form field fixture", onClick = { textInputOnly = true })
        UiAction("Open real RPC fixture", onClick = { rpcOnly = true })
        UiAction("Check RPC descriptor assembly", onClick = { descriptors = checkRpcDescriptorAssembly() })
        UiText(descriptors)
        UiAction("Check RPC serialization", onClick = { serialization = checkRpcSerialization() })
        UiText(serialization)
        UiEditor(
            text, cursor,
            onChange = { changed, offset -> text = changed; cursor = offset; null },
            onSubmit = { _, _ -> submissions++ },
            label = "Fixture draft",
            autoFocus = true,
            submitKey = submitKey,
        )
        UiText("Submitted $submissions: $text")
        UiAction("Toggle fixture submit key", onClick = {
            submitKey = if (submitKey == UiSubmitKey.Enter) UiSubmitKey.CtrlEnter else UiSubmitKey.Enter
        })
        UiText("Fixture submit key: $submitKey")
        UiAction("Open fixture delete confirmation", onClick = { confirmation = true }, enabled = !confirmation)
        if (confirmation) {
            val child = remember {
                createSessionDeleteViewModel(41, "fixture", SessionDeleteDependencies { deletions++; true })
            }
            SessionDeleteContent(
                child,
                onCancel = { confirmation = false; result = "Fixture cancelled" },
                onResult = { result = "Fixture deleted: $it"; confirmation = false },
            )
        }
        UiText("$result; deletions=$deletions")
        UiAction("Open fixture rename", onClick = { renaming = true }, enabled = !renaming)
        if (renaming) {
            val child = remember {
                createSessionRenameViewModel(title, SessionRenameDependencies { title = it })
            }
            SessionRenameContent(child, onSubmitted = { renaming = false })
            UiAction("Close fixture rename", onClick = { renaming = false })
        }
        UiText("Fixture title: $title")
    }
}
