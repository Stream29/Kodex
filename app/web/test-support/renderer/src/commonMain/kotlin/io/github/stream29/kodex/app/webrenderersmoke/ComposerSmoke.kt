package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.app.agent.contract.*
import io.github.stream29.kodex.app.ui.UiAction
import io.github.stream29.kodex.app.ui.UiColumn
import io.github.stream29.kodex.app.ui.UiText
import io.github.stream29.kodex.cli.agent.ComposerContent
import io.github.stream29.kodex.cli.agent.createComposerViewModel
import io.github.stream29.kodex.cli.settings.NewLineKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
internal fun ComposerSmoke(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var accepted by remember { mutableStateOf("None") }
    var fail by remember { mutableStateOf(false) }
    var newLineKey by remember { mutableStateOf(NewLineKey.ShiftEnter) }
    val runtimeFixture = remember {
        object : ComposerRuntimePort {
            override val running = MutableStateFlow(false)
            override val pendingSteer = MutableStateFlow<List<StableIndexEvent.Steerable>>(emptyList())
        }
    }
    val model = remember {
        createComposerViewModel(ComposerOwnerId("isolated-composer-fixture"), object : ComposerDependencies {
            override val runtime = runtimeFixture
            override val requestInput = object : ComposerRequestInputPort {
                override val presentation = MutableStateFlow<ComposerRequestInputPresentation>(
                    ComposerRequestInputPresentation.None,
                )
            }
            override val submit = ComposerSubmitPort { owner, content ->
                delay(300)
                check(!fail) { "Fixture append failed" }
                accepted = "Submit ${owner.value}: $content"
            }
            override val steer = ComposerSteerPort { owner, content ->
                runtimeFixture.pendingSteer.value += StableUserMessage(content)
                accepted = "Steer ${owner.value}: $content"
            }
            override val resume = ComposerResumePort {}
            override val cancellation = ComposerCancellationPort {}
            override val failures = ComposerFailureReporter { _, failure -> accepted = "Failure: ${failure.message}" }
        }, scope)
    }
    DisposableEffect(model) { onDispose(model::close) }
    val state by model.state.collectAsState()
    UiColumn {
        UiText("Shared Composer — injected in-memory ports, not RPC")
        ComposerContent(model, newLineKey)
        UiText("Composer mode: ${state.mode}; revision=${state.revision}; cursor=${state.cursorOffset}")
        UiText("Fixture accepted: $accepted")
        UiAction("Toggle running fixture", onClick = {
            runtimeFixture.running.value = !runtimeFixture.running.value
        })
        UiAction("Toggle append failure fixture", onClick = { fail = !fail })
        UiText("Append failure enabled: $fail")
        UiAction("Toggle Composer newline fixture", onClick = {
            newLineKey = if (newLineKey == NewLineKey.Enter) NewLineKey.ShiftEnter else NewLineKey.Enter
        })
        UiText("Composer newline: $newLineKey")
        UiAction("Back to widgets fixture", onClick = onBack)
    }
}
