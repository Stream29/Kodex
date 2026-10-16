package io.github.stream29.kodex.cli.accountusage

import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.accountusage.AccountUsageDependencies
import io.github.stream29.kodex.app.accountusage.AccountUsageState
import io.github.stream29.kodex.app.accountusage.createAccountUsageViewModel
import io.github.stream29.kodex.app.settings.contract.SettingsAccountUsageState
import io.github.stream29.kodex.openai.accountusage.CodexAccountRateLimit
import io.github.stream29.kodex.openai.accountusage.CodexAccountRateLimitWindow
import io.github.stream29.kodex.openai.accountusage.CodexAccountTokenUsage
import io.github.stream29.kodex.openai.accountusage.CodexAccountUsageSection
import io.github.stream29.kodex.openai.accountusage.CodexAccountUsageSnapshot
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetCredit
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetCredits
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*
import kotlin.time.Instant

val accountUsageComponentTest by testSuite {
    val usage = renderSnapshot()
    val branches = listOf(
        RenderUsageCase("unavailable", SettingsAccountUsageState.Unavailable, "Sign in to view Codex usage.", false),
        RenderUsageCase("initial loading", SettingsAccountUsageState.Loading(), "Loading usage…", false),
        RenderUsageCase("refresh fallback", SettingsAccountUsageState.Loading(usage), "Refreshing usage…", true),
        RenderUsageCase("available", SettingsAccountUsageState.Available(usage), "Lifetime tokens: 123,456", true),
        RenderUsageCase("failure without fallback", SettingsAccountUsageState.Failed("Safe failure"), "Safe failure", false),
        RenderUsageCase("failure with fallback", SettingsAccountUsageState.Failed("Safe failure", usage), "Safe failure", true),
        RenderUsageCase("redeeming", SettingsAccountUsageState.Redeeming(usage), "Using a reset…", true),
    )
    for (case in branches) {
        test("complete usage branch ${case.name}") {
            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    Column(Modifier.width(140)) {
                        AccountUsageContent(AccountUsageState(case.usage), {}, {}, {})
                    }
                }
                assertTrue("Codex usage" in snapshot, snapshot)
                assertTrue(case.text in snapshot, snapshot)
                assertEquals(case.fallback, "Codex: 5h 42% used (resets in 15m) 7d 10% used (resets in 2d)" in snapshot)
                assertEquals(case.usage != SettingsAccountUsageState.Unavailable, "[Refresh]" in snapshot)
                assertEquals(case.usage != SettingsAccountUsageState.Unavailable, "[Use reset]" in snapshot)
                if (!case.fallback) assertFalse("Lifetime tokens:" in snapshot, snapshot)
                assertFalse("choose a reset" in snapshot, snapshot)
                assertFalse("Use this reset?" in snapshot, snapshot) // No reset dialogs live here.
            }
        }
    }
    test("null counts and details remain unavailable while known zero renders zero") {
        for (tokens in listOf(null, 0L)) {
            for (count in listOf(null, 0L)) {
                runMosaicTest {
                    val snapshot = setContentAndSnapshot {
                        Column(Modifier.width(140)) {
                            AccountUsageContent(AccountUsageState(SettingsAccountUsageState.Available(
                                renderSnapshot(tokens = tokens, count = count, credits = emptyList()))), {}, {}, {})
                        }
                    }
                    assertTrue("Lifetime tokens: ${tokens ?: "unavailable"}" in snapshot, snapshot)
                    assertTrue(if (count == null) "Usage limit resets: unavailable" in snapshot
                        else "Usage limit resets: 0 available" in snapshot, snapshot)
                    assertFalse("Reset details unavailable" in snapshot, snapshot)
                }
            }
        }
    }
    test("count-only details unavailable tells user to refresh never backend auto-selection") {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Column(Modifier.width(140)) {
                    AccountUsageContent(AccountUsageState(SettingsAccountUsageState.Available(
                        renderSnapshot(credits = null, unavailable = setOf(
                            CodexAccountUsageSection.ResetCreditDetails, CodexAccountUsageSection.TokenUsage)))),
                        {}, {}, {})
                }
            }
            assertTrue("Reset details unavailable. Refresh usage" in snapshot, snapshot)
            assertTrue("Token activity details unavailable." in snapshot, snapshot)
            assertFalse("backend will choose" in snapshot, snapshot)
        }
    }
    test("known empty rate limits and nullable token section do not produce fake windows or zeroes") {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Column(Modifier.width(140)) {
                    AccountUsageContent(AccountUsageState(SettingsAccountUsageState.Available(
                        renderSnapshot().copy(rateLimits = emptyList(), tokenUsage = null))), {}, {}, {})
                }
            }
            assertTrue("Rate limits unavailable" in snapshot, snapshot)
            assertTrue("Lifetime tokens: unavailable" in snapshot, snapshot)
            assertFalse("% used" in snapshot, snapshot)
        }
    }
    test("window duration delay exhausted and grouped formatting preserve baseline") {
        val cases = listOf(
            Triple(0L, -1L, "0s 42% used (resets now)"),
            Triple(30L, 45L, "30s 42% used (resets in 45s)"),
            Triple(120L, 120L, "2m 42% used (resets in 2m)"),
            Triple(3_600L, 3_600L, "1h 42% used (resets in 1h)"),
            Triple(86_400L, 86_400L, "1d 42% used (resets in 1d)"),
        )
        for ((duration, delay, expected) in cases) {
            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    val limit = usage.rateLimits.single().copy(
                        allowed = false,
                        primaryWindow = usage.rateLimits.single().primaryWindow!!.copy(
                            durationSeconds = duration, resetAfterSeconds = delay),
                        secondaryWindow = null,
                    )
                    Column(Modifier.width(140)) {
                        AccountUsageContent(AccountUsageState(SettingsAccountUsageState.Available(
                            usage.copy(rateLimits = listOf(limit)))), {}, {}, {})
                    }
                }
                assertTrue("Codex: $expected limit reached" in snapshot, snapshot)
            }
        }
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Column(Modifier.width(140)) {
                    AccountUsageContent(AccountUsageState(SettingsAccountUsageState.Available(
                        usage.copy(rateLimits = listOf(usage.rateLimits.single().copy(
                            primaryWindow = null, secondaryWindow = null, limitReached = true))))), {}, {}, {})
                }
            }
            assertTrue("Codex: unavailable limit reached" in snapshot, snapshot)
        }
    }
    for (initial in listOf(SettingsAccountUsageState.Loading(usage), SettingsAccountUsageState.Redeeming(usage))) {
        test("disabled refresh and reset keyboard surface in $initial emits nothing") {
            var refreshes = 0
            var resets = 0
            runMosaicTest {
                setContentAndSnapshot {
                    Column(Modifier.width(140)) {
                        AccountUsageContent(AccountUsageState(initial), { refreshes++ }, { resets++ }, {})
                    }
                }
                repeat(4) {
                    sendKeyEvent(KeyboardEvent(codepoint = 9))
                    sendKeyEvent(KeyboardEvent(codepoint = 13))
                }
                assertEquals(0, refreshes)
                assertEquals(0, resets)
            }
        }
    }
    test("real child renderer refreshes once per click and reset only sends an intent") {
        val deps = RenderUsagePorts(SettingsAccountUsageState.Available(usage))
        val owner = Job()
        val vm = createAccountUsageViewModel(deps, CoroutineScope(Dispatchers.Unconfined + owner))
        try {
            runMosaicTest {
                setContentAndSnapshot {
                    Column(Modifier.width(140)) { AccountUsageComponent(vm) }
                }
                assertEquals(0, deps.refreshes) // Not a composition/constructor side effect.
                sendKeyEvent(KeyboardEvent(codepoint = 13))
                flushUsageInput()
                sendKeyEvent(KeyboardEvent(codepoint = 13))
                flushUsageInput()
                assertEquals(2, deps.refreshes)
                sendKeyEvent(KeyboardEvent(codepoint = 9))
                flushUsageInput()
                sendKeyEvent(KeyboardEvent(codepoint = 13))
                flushUsageInput()
                assertEquals(1, deps.resets)
                assertEquals(2, deps.refreshes)
            }
        } finally { vm.close(); owner.cancel() }
    }
    test("known zero reset credits disable reset but count-only availability sends selection intent") {
        for (count in listOf(0L, 2L)) {
            val deps = RenderUsagePorts(SettingsAccountUsageState.Available(
                renderSnapshot(count = count, credits = null)))
            val owner = Job()
            val vm = createAccountUsageViewModel(deps, CoroutineScope(Dispatchers.Unconfined + owner))
            try {
                runMosaicTest {
                    setContentAndSnapshot {
                        Column(Modifier.width(140)) { AccountUsageComponent(vm) }
                    }
                    sendKeyEvent(KeyboardEvent(codepoint = 9))
                    flushUsageInput()
                    sendKeyEvent(KeyboardEvent(codepoint = 13))
                    flushUsageInput()
                    assertEquals(if (count == 0L) 0 else 1, deps.resets)
                    assertEquals(if (count == 0L) 1 else 0, deps.refreshes)
                    assertTrue(vm.state.value.usage is SettingsAccountUsageState.Available)
                }
            } finally { vm.close(); owner.cancel() }
        }
    }
    test("failed command reports shared safe banner and Dismiss does not refresh or clear source") {
        val deps = RenderUsagePorts(SettingsAccountUsageState.Failed("Safe source failure", usage))
        deps.refreshError = IllegalStateException("raw-secret-diagnostic")
        val owner = Job()
        val vm = createAccountUsageViewModel(deps, CoroutineScope(Dispatchers.Unconfined + owner))
        try {
            runMosaicTest {
                setContentAndSnapshot {
                    Column(Modifier.width(140)) { AccountUsageComponent(vm) }
                }
                sendKeyEvent(KeyboardEvent(codepoint = 13))
                val failure = awaitSnapshot()
                assertTrue("A settings operation failed." in failure, failure)
                assertTrue("Safe source failure" in failure, failure)
                assertFalse("raw-secret-diagnostic" in failure, failure)
                // Disabled reset is skipped, so the next focus target is Dismiss.
                sendKeyEvent(KeyboardEvent(codepoint = 9))
                flushUsageInput()
                sendKeyEvent(KeyboardEvent(codepoint = 13))
                val dismissed = awaitSnapshot()
                assertFalse("A settings operation failed." in dismissed, dismissed)
                assertTrue("Safe source failure" in dismissed, dismissed)
                assertEquals(1, deps.dismissals)
                assertEquals(1, deps.refreshes)
            }
        } finally { vm.close(); owner.cancel() }
    }
    test("closed renders nothing and shared failure delegation avoids duplicate host banner") {
        for (closed in listOf(false, true)) {
            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    Column(Modifier.width(140)) {
                        AccountUsageContent(AccountUsageState(SettingsAccountUsageState.Available(usage),
                            operationFailure = true, closed = closed), {}, {}, {}, showOperationFailure = false)
                    }
                }
                assertEquals(!closed, "Codex usage" in snapshot)
                assertFalse("A settings operation failed." in snapshot, snapshot)
            }
        }
    }
}

private data class RenderUsageCase(
    val name: String,
    val usage: SettingsAccountUsageState,
    val text: String,
    val fallback: Boolean,
)

private fun renderSnapshot(
    tokens: Long? = 123_456,
    count: Long? = 2,
    credits: List<CodexRateLimitResetCredit>? = listOf(CodexRateLimitResetCredit(
        "credit", grantedAt = null, expiresAt = null)),
    unavailable: Set<CodexAccountUsageSection> = emptySet(),
): CodexAccountUsageSnapshot = CodexAccountUsageSnapshot(
    rateLimits = listOf(CodexAccountRateLimit(
        "Codex", "codex", allowed = true, limitReached = false,
        primaryWindow = CodexAccountRateLimitWindow(42, 18_000, 900, Instant.parse("2026-10-02T01:15:00Z")),
        secondaryWindow = CodexAccountRateLimitWindow(10, 604_800, 172_800, Instant.parse("2026-10-04T01:00:00Z")),
    )),
    resetCredits = CodexRateLimitResetCredits(count, credits),
    tokenUsage = CodexAccountTokenUsage(lifetimeTokens = tokens),
    unavailableSections = unavailable,
    fetchedAt = Instant.parse("2026-10-02T01:00:00Z"),
)

private suspend fun TestMosaic<String>.flushUsageInput(): String =
    try { awaitSnapshot() } catch (_: TimeoutCancellationException) {
        draw().render(AnsiLevel.NONE, supportsKittyUnderlines = false)
    }

private class RenderUsagePorts(initial: SettingsAccountUsageState) : AccountUsageDependencies {
    override val usage = MutableStateFlow(initial)
    override val operationFailure = MutableStateFlow(false)
    var refreshes = 0
    var resets = 0
    var dismissals = 0
    var refreshError: Throwable? = null
    val resetIntent = CompletableDeferred<Unit>()
    override suspend fun refresh() { refreshes++; refreshError?.let { throw it } }
    override fun requestReset() { resets++; resetIntent.complete(Unit) }
    override fun reportFailure(failure: Throwable) { operationFailure.value = true }
    override fun dismissFailure() { dismissals++; operationFailure.value = false }
}
