package io.github.stream29.kodex.app.webrenderersmoke

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.settings.contract.*
import io.github.stream29.kodex.app.ui.UiText
import io.github.stream29.kodex.cli.settings.OpenAiLoginContent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.test.*

val offscreenLoginTest by testSuite {
    val cases = listOf(
        OpenAiLoginState.Ready to "Open browser",
        OpenAiLoginState.Preparing to "Preparing secure sign-in…",
        OpenAiLoginState.WaitingForAuthorization(7) to "Waiting for browser sign-in…",
        OpenAiLoginState.BrowserOpenFailed(7) to "Retry browser",
        OpenAiLoginState.Completed to "Sign-in complete",
        OpenAiLoginState.Failed("Safe failure") to "Safe failure",
    )
    for ((state, expected) in cases) {
        test("shared login renders $state without launching or dismissing") { offscreenTest {
            val child = LoginRenderChild(state)
            var dismissals = 0
            val scene = OffscreenScene.create {
                OpenAiLoginContent(child, { dismissals++ }, { error("No effect should be launched") }) { title, _, content ->
                    UiText(title)
                    content()
                }
            }
            try {
                scene.settle { scene.hasText(expected) }
                assertEquals(0, dismissals)
                assertEquals(state != OpenAiLoginState.Completed, scene.hasText("Cancel"))
                when (state) {
                    OpenAiLoginState.Ready -> { scene.click("Open browser"); assertEquals(1, child.starts) }
                    is OpenAiLoginState.BrowserOpenFailed -> {
                        scene.click("Retry browser")
                        assertEquals(listOf(7L), child.retries)
                    }
                    is OpenAiLoginState.Failed -> { scene.click("Try again"); assertEquals(1, child.starts) }
                    else -> Unit
                }
                scene.click(if (state == OpenAiLoginState.Completed) "Close" else "Cancel")
                assertEquals(1, child.cancellations)
                assertEquals(1, dismissals)
                scene.frame()
            } finally { scene.close() }
            assertTrue(child.closed)
        } }
    }
    for (accepted in listOf(true, false)) {
        test("shared login reports launcher acceptance $accepted for active effect only") { offscreenTest {
            val child = LoginRenderChild(OpenAiLoginState.WaitingForAuthorization(7))
            val launched = mutableListOf<String>()
            child.output.trySend(OpenAiLoginEffect.OpenExternalUrl(6, "https://example.invalid/stale"))
            child.output.trySend(OpenAiLoginEffect.OpenExternalUrl(7, "https://example.invalid/transient"))
            val scene = OffscreenScene.create {
                OpenAiLoginContent(child, {}, { launched += it; accepted }) { title, _, content ->
                    UiText(title)
                    content()
                }
            }
            try {
                scene.settle { child.reports.size == 1 }
                assertEquals(listOf("https://example.invalid/transient"), launched)
                assertEquals(listOf(7L to accepted), child.reports)
                assertFalse(scene.hasText("Sign-in complete"))
                assertFalse(scene.hasText("https://example.invalid/transient"))
            } finally { scene.close() }
            assertTrue(child.closed)
        } }
    }
}

internal class LoginRenderChild(initial: OpenAiLoginState) : OpenAiLoginViewModel {
    override val state = MutableStateFlow(initial)
    val output = Channel<OpenAiLoginEffect>(Channel.BUFFERED)
    override val effects = output.receiveAsFlow()
    var starts = 0
    var cancellations = 0
    var closed = false
    val retries = mutableListOf<Long>()
    val reports = mutableListOf<Pair<Long, Boolean>>()
    override fun start() { starts++ }
    override fun retryBrowser(attemptId: Long) { retries += attemptId }
    override fun cancel() { cancellations++ }
    override fun isActive(attemptId: Long): Boolean = !closed && attemptId == 7L
    override fun onBrowserOpened(attemptId: Long) { reports += attemptId to true }
    override fun onBrowserOpenFailed(attemptId: Long) { reports += attemptId to false }
    override fun close() { closed = true; output.close() }
}
