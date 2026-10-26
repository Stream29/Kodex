package io.github.stream29.kodex.app.webrenderersmoke

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.accountusage.*
import io.github.stream29.kodex.app.settings.contract.SettingsAccountUsageState
import io.github.stream29.kodex.cli.accountusage.AccountUsageComponent
import io.github.stream29.kodex.cli.accountusage.AccountUsageContent
import io.github.stream29.kodex.openai.accountusage.CodexAccountTokenUsage
import io.github.stream29.kodex.openai.accountusage.CodexAccountUsageSnapshot
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetCredits
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*
import kotlin.time.Instant

val offscreenAccountUsageTest by testSuite {
    val snapshot = usageSnapshot(0)
    val cases = listOf(
        SettingsAccountUsageState.Unavailable to "Sign in to view Codex usage.",
        SettingsAccountUsageState.Loading() to "Loading usage…",
        SettingsAccountUsageState.Loading(snapshot) to "Refreshing usage…",
        SettingsAccountUsageState.Available(snapshot) to "Lifetime tokens: 0",
        SettingsAccountUsageState.Failed("Safe failure") to "Safe failure",
        SettingsAccountUsageState.Failed("Safe failure", snapshot) to "Safe failure",
        SettingsAccountUsageState.Redeeming(snapshot) to "Using a reset…",
    )
    for ((index, case) in cases.withIndex()) {
        val (usage, expected) = case
        test("shared graphical usage branch $index $expected") { offscreenTest {
            val state = AccountUsageState(usage)
            var refreshes = 0
            var resets = 0
            val scene = OffscreenScene.create { AccountUsageContent(state, { refreshes++ }, { resets++ }, {}) }
            try {
                scene.settle { scene.hasText(expected) }
                assertTrue(scene.hasText("Codex usage"))
                assertEquals(state.actionsVisible, scene.hasText("Refresh"))
                assertEquals(state.actionsVisible, scene.hasText("Use reset"))
                if (state.actionsVisible) {
                    scene.click("Refresh")
                    scene.click("Use reset")
                    assertEquals(if (state.refreshEnabled) 1 else 0, refreshes)
                    assertEquals(if (state.resetEnabled) 1 else 0, resets)
                }
                assertFalse(scene.hasText("Use this reset?"))
                scene.frame()
            } finally { scene.close() }
        } }
    }
    test("real usage child preserves borrowed lifetime and sends only refresh reset and dismiss intents") { offscreenTest {
        val usage = MutableStateFlow<SettingsAccountUsageState>(SettingsAccountUsageState.Available(usageSnapshot(2)))
        val failure = MutableStateFlow(false)
        var refreshes = 0
        var resets = 0
        var dismissals = 0
        val owner = CoroutineScope(currentCoroutineContext() + Job(currentCoroutineContext()[Job]))
        val child = createAccountUsageViewModel(object : AccountUsageDependencies {
            override val usage = usage
            override val operationFailure = failure
            override suspend fun refresh() { refreshes++ }
            override fun requestReset() { resets++ }
            override fun reportFailure(failure: Throwable) { throw AssertionError("Unexpected command failure", failure) }
            override fun dismissFailure() { dismissals++; failure.value = false }
        }, owner)
        var output: OffscreenScene? = null
        try {
            val scene = OffscreenScene.create { AccountUsageComponent(child) }.also { output = it }
            scene.settle { scene.hasText("Usage limit resets: 2 available") }
            assertEquals(0, refreshes)
            scene.click("Refresh")
            scene.settle { refreshes == 1 }
            scene.click("Refresh")
            scene.settle { refreshes == 2 }
            scene.click("Use reset")
            assertEquals(1, resets)
            usage.value = SettingsAccountUsageState.Loading(usageSnapshot(2))
            scene.settle { scene.hasText("Refreshing usage…") }
            scene.click("Refresh")
            scene.click("Use reset")
            assertEquals(2, refreshes)
            assertEquals(1, resets)
            failure.value = true
            scene.settle { scene.hasText("A settings operation failed.") }
            scene.click("Dismiss")
            scene.settle { !scene.hasText("A settings operation failed.") }
            assertEquals(1, dismissals)
            assertIs<SettingsAccountUsageState.Loading>(usage.value)
            scene.close()
            output = null
            assertFalse(child.state.value.closed)
            child.refresh()
            withTimeout(5_000) { while (refreshes != 3) delay(1) }
        } finally {
            output?.close()
            child.close()
            owner.coroutineContext.job.cancelAndJoin()
        }
    } }
}

private fun usageSnapshot(count: Long) = CodexAccountUsageSnapshot(
    rateLimits = emptyList(),
    resetCredits = CodexRateLimitResetCredits(count, null),
    tokenUsage = CodexAccountTokenUsage(lifetimeTokens = 0),
    unavailableSections = emptySet(),
    fetchedAt = Instant.fromEpochMilliseconds(0),
)
