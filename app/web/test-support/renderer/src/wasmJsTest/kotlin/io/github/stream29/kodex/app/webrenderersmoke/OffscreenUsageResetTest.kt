package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.settings.contract.UsageResetOption
import io.github.stream29.kodex.app.settings.contract.UsageResetRequest
import io.github.stream29.kodex.app.settings.contract.UsageResetState
import io.github.stream29.kodex.app.ui.compose.rememberComposeSettingsRootRenderer
import io.github.stream29.kodex.cli.settings.SettingsModalFrame
import io.github.stream29.kodex.cli.usagereset.UsageResetContent
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetOutcome
import kotlin.test.*

val offscreenUsageResetTest by testSuite {
    val option = UsageResetOption("credit-995", "Specific credit", "Captured credit details.", null)
    for ((width, height) in listOf(1024 to 768, 360 to 640, 320 to 240)) {
        for (fontScale in listOf(1f, 1.5f)) {
            test("reset Modal keeps safe Back focus and exact long option ${width}x$height font=$fontScale") { offscreenTest {
                val expected = UsageResetState.Confirming(option.copy(
                    title = "完整 captured credit ".repeat(20), description = "Complete credit details. ".repeat(35),
                ))
                val calls = ResetCalls()
                val scene = OffscreenScene.create(width = width, height = height) {
                    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) { ResetContent(expected, calls) }
                }
                try {
                    scene.settle { scene.hasText("Go back") && scene.text("Go back").boundsInWindow.height > 0 && scene.nodes().any {
                        it.config.getOrNull(SemanticsProperties.Focused) == true &&
                            it.children.any { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Go back" } == true }
                    } }
                    val backBounds = scene.text("Go back").boundsInWindow
                    assertTrue(backBounds.height > 0 && backBounds.bottom <= height,
                        "Focused Go back must be visible: $backBounds in ${width}x$height; scroll=" +
                            scene.nodes().mapNotNull { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) }.map { it.value() to it.maxValue() })
                    scene.key(Key.Enter)
                    assertEquals(1, calls.backs)
                    assertTrue(calls.confirmed.isEmpty(), "Safe Back focus must not invoke Use reset.")
                    for (scroll in scene.nodes().mapNotNull { it.config.getOrNull(SemanticsActions.ScrollBy)?.action }) {
                        scroll(0f, 10_000f)
                    }
                    scene.settle { scene.text("Use reset").boundsInWindow.height > 0 }
                    assertTrue(scene.text("Use reset").boundsInWindow.bottom <= height,
                        "Use reset bounds: ${scene.text("Use reset").boundsInWindow} in ${width}x$height")
                    scene.click("Use reset")
                    assertSame(expected, calls.confirmed.single())
                    assertEquals(0, calls.retries + calls.refreshes)
                    scene.frame()
                } finally { scene.close() }
            } }
        }
    }

    test("choosing sends a specific id and a legacy null option stays disabled") { offscreenTest {
        val state = UsageResetState.Choosing(UsageResetRequest(2, listOf(option, option.copy(
            creditId = null, title = "Unavailable credit",
        ))))
        val calls = ResetCalls()
        val scene = OffscreenScene.create { ResetContent(state, calls) }
        try {
            scene.settle { scene.hasText("2 usage limit resets available.") }
            scene.click("Unavailable credit")
            assertTrue(calls.selected.isEmpty())
            scene.click("Specific credit")
            assertEquals(listOf("credit-995"), calls.selected)
            assertTrue(calls.confirmed.isEmpty())
            scene.click("Cancel")
            assertEquals(1, calls.dismissals)
            scene.frame()
        } finally { scene.close() }
    } }
    test("confirmation submits the rendered object by identity without selecting or refreshing") { offscreenTest {
        val expected = UsageResetState.Confirming(option)
        val calls = ResetCalls()
        val scene = OffscreenScene.create { ResetContent(expected, calls) }
        try {
            scene.settle { scene.hasText("Use this reset?") }
            scene.click("Use reset")
            assertSame(expected, calls.confirmed.single())
            assertTrue(calls.selected.isEmpty())
            assertEquals(0, calls.refreshes)
            scene.click("Go back")
            assertEquals(1, calls.backs)
        } finally { scene.close() }
    } }
    test("Hidden mounts no frame and Consuming exposes no action or effective dismissal") { offscreenTest {
        for (state in listOf(UsageResetState.Hidden, UsageResetState.Consuming(option))) {
            val calls = ResetCalls()
            val scene = OffscreenScene.create { ResetContent(state, calls) }
            try {
                if (state == UsageResetState.Hidden) {
                    scene.advance()
                    assertEquals(0, calls.frames)
                } else {
                    scene.settle { scene.hasText("Resetting your usage…") }
                    assertFalse(scene.hasText("Close") || scene.hasText("Cancel") || scene.hasText("Use reset"))
                    assertNotNull(calls.dismiss).invoke()
                }
                assertEquals(0, calls.dismissals)
                assertTrue(calls.confirmed.isEmpty())
                assertEquals(0, calls.refreshes)
            } finally { scene.close() }
        }
    } }
    test("unknown result requires explicit retry and missing details require explicit refresh") { offscreenTest {
        val cases = listOf(
            UsageResetState.ConsumeFailed(option) to "Try again",
            UsageResetState.PreparationFailed to "Refresh",
        )
        for ((state, action) in cases) {
            val calls = ResetCalls()
            val scene = OffscreenScene.create { ResetContent(state, calls) }
            try {
                scene.settle { scene.hasText(action) }
                assertTrue(calls.confirmed.isEmpty())
                assertEquals(0, calls.retries + calls.refreshes)
                scene.click(action)
                assertEquals(if (action == "Try again") 1 else 0, calls.retries)
                assertEquals(if (action == "Refresh") 1 else 0, calls.refreshes)
                assertTrue(calls.confirmed.isEmpty())
            } finally { scene.close() }
        }
    } }
    val outcomes = listOf(
        UsageResetState.Completed(CodexRateLimitResetOutcome.Reset, true) to "Usage reset.",
        UsageResetState.Completed(CodexRateLimitResetOutcome.NothingToReset, true) to
            "Your usage does not need a reset right now.",
        UsageResetState.Completed(CodexRateLimitResetOutcome.AlreadyRedeemed, true) to
            "This reset was already used successfully.",
        UsageResetState.Completed(CodexRateLimitResetOutcome.NoCredit, true) to "That reset is no longer available.",
        UsageResetState.Completed(CodexRateLimitResetOutcome.NoCredit, false) to "No usage limit resets are available.",
    )
    for ((state, message) in outcomes) {
        test("known reset outcome $message") { offscreenTest {
            val calls = ResetCalls()
            val scene = OffscreenScene.create { ResetContent(state, calls) }
            try {
                scene.settle { scene.hasText(message) }
                scene.click("Close")
                assertEquals(1, calls.dismissals)
                assertTrue(calls.confirmed.isEmpty())
                assertEquals(0, calls.retries + calls.refreshes)
                scene.frame()
            } finally { scene.close() }
        } }
    }
}

@Composable
private fun ResetContent(state: UsageResetState, calls: ResetCalls) {
    val renderer = rememberComposeSettingsRootRenderer()
    UsageResetContent(
        state, onSelect = { calls.selected += it }, onConfirm = { calls.confirmed += it },
        onBack = { calls.backs++ }, onRetry = { calls.retries++ }, onRefresh = { calls.refreshes++ },
        onDismiss = { calls.dismissals++ },
    ) { title, dismiss, content ->
        calls.frames++
        calls.dismiss = dismiss
        renderer.Modal(SettingsModalFrame.UsageReset, title, dismiss, content)
    }
}

private class ResetCalls {
    val selected = mutableListOf<String>()
    val confirmed = mutableListOf<UsageResetState.Confirming>()
    var backs = 0
    var retries = 0
    var refreshes = 0
    var dismissals = 0
    var frames = 0
    var dismiss: (() -> Unit)? = null
}
