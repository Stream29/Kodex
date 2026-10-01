package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Box
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.settings.contract.*
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.utils.externalurl.OpenExternalUrlResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val openAiLoginRenderContractTest by testSuite {
    val renderCases = listOf(
        RenderCase("ready", OpenAiLoginState.Ready, "Open browser", "[Cancel]"),
        RenderCase("preparing", OpenAiLoginState.Preparing, "Preparing secure sign-in", "[Cancel]"),
        RenderCase("waiting", OpenAiLoginState.WaitingForAuthorization(7), "Waiting for browser sign-in", "[Cancel]"),
        RenderCase("browser failure", OpenAiLoginState.BrowserOpenFailed(7), "Retry browser", "[Cancel]"),
        RenderCase("completed", OpenAiLoginState.Completed, "Sign-in complete", "[Close]"),
        RenderCase("failed", OpenAiLoginState.Failed("Safe failure"), "Safe failure", "[Try again]"),
    )
    for (case in renderCases) {
        test("renders ${case.name} from spec state without backend or browser access") {
            val vm = RenderLoginViewModel(case.state)
            var visible by mutableStateOf(true)
            try {
                runMosaicTest {
                    setContentAndSnapshot {
                        Box {
                            TuiPopupHost(modifier = Modifier.width(80).height(24)) {
                                if (visible) {
                                    OpenAiLoginPopup(vm, {}, openUrl = { error("No launch expected") })
                                }
                            }
                        }
                    }
                    val snapshot = awaitSnapshot()
                    assertTrue(case.message in snapshot, snapshot)
                    assertTrue(case.action in snapshot, snapshot)
                    assertFalse("transient-secret" in snapshot, snapshot)
                    if (case.state == OpenAiLoginState.Completed) {
                        assertFalse("[Cancel]" in snapshot, snapshot)
                    }
                    // Assert actual composition removal, not test-harness shutdown timing.
                    visible = false
                    awaitSnapshot()
                    assertTrue(vm.closed)
                }
            } finally {
                vm.close()
            }
        }
    }

    for (outcome in listOf(OpenExternalUrlResult.Started, OpenExternalUrlResult.Failed("Launcher unavailable"))) {
        test("reports $outcome for the exact active effect and never launches stale effects") {
            val vm = RenderLoginViewModel(OpenAiLoginState.WaitingForAuthorization(7))
            vm.activeId = 7
            vm.output.trySend(OpenAiLoginEffect.OpenExternalUrl(6, "https://example.invalid/stale"))
            vm.output.trySend(OpenAiLoginEffect.OpenExternalUrl(7, "https://example.invalid/transient-secret"))
            val launched = mutableListOf<String>()
            try {
                runMosaicTest {
                    setContentAndSnapshot {
                        Box {
                            TuiPopupHost(modifier = Modifier.width(80).height(24)) {
                                OpenAiLoginPopup(
                                    vm,
                                    {},
                                    openUrl = { url -> launched += url; outcome },
                                )
                            }
                        }
                    }
                    assertEquals(7L, withTimeout(1_000) { vm.reported.await() })
                    assertEquals(listOf("https://example.invalid/transient-secret"), launched)
                    assertEquals(outcome is OpenExternalUrlResult.Failed, vm.reportedFailure)
                    assertFalse("transient-secret" in awaitSnapshot())
                }
            } finally {
                vm.close()
            }
        }
    }
}

private data class RenderCase(
    val name: String,
    val state: OpenAiLoginState,
    val message: String,
    val action: String,
)

private class RenderLoginViewModel(initial: OpenAiLoginState) : OpenAiLoginViewModel {
    override val state = MutableStateFlow(initial)
    val output = Channel<OpenAiLoginEffect>(Channel.BUFFERED)
    override val effects = output.receiveAsFlow()
    var activeId: Long? = null
    var closed = false
    val reported = CompletableDeferred<Long>()
    var reportedFailure = false

    override fun start() = Unit
    override fun retryBrowser(attemptId: Long) = Unit
    override fun cancel() = Unit
    override fun onBrowserOpened(attemptId: Long) {
        reported.complete(attemptId)
    }
    override fun onBrowserOpenFailed(attemptId: Long) {
        state.value = OpenAiLoginState.BrowserOpenFailed(attemptId)
        reportedFailure = true
        reported.complete(attemptId)
    }
    override fun isActive(attemptId: Long) = !closed && attemptId == activeId
    override fun close() {
        closed = true
        output.close()
    }
}
