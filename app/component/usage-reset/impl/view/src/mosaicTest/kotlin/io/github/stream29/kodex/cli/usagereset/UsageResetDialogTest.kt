package io.github.stream29.kodex.cli.usagereset

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Text
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.settings.contract.SettingsAccountUsageState
import io.github.stream29.kodex.app.settings.contract.UsageResetOption
import io.github.stream29.kodex.app.settings.contract.UsageResetRequest
import io.github.stream29.kodex.app.settings.contract.UsageResetState
import io.github.stream29.kodex.app.usagereset.contract.UsageResetDependencies
import io.github.stream29.kodex.app.usagereset.contract.UsageResetViewModel
import io.github.stream29.kodex.app.usagereset.createUsageResetViewModel
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.openai.accountusage.CodexAccountUsageSnapshot
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetCredit
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetCredits
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*
import kotlin.time.Instant

val usageResetDialogTest by testSuite {
    test("Hidden renders no dialog and removing renderer does not close reusable child") {
        val vm = RecordingReset(UsageResetState.Hidden)
        var visible by mutableStateOf(true)
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Box {
                    TuiPopupHost(Modifier.width(100).height(30)) {
                        Text("background")
                        if (visible) UsageResetDialogHost(vm)
                    }
                }
            }
            assertTrue("background" in snapshot, snapshot)
            assertFalse("Usage limit resets" in snapshot, snapshot)
            visible = false
            awaitSnapshot()
            assertFalse(vm.closed)
            assertEquals(0, vm.events)
        }
        vm.close()
    }

    test("picker preserves detail order, descriptions, both expiry forms and Cancel-first focus") {
        val vm = RecordingReset(choices())
        runMosaicTest {
            val snapshot = setContentAndSnapshot { RenderRecording(vm) }
            assertTrue("2 usage limit resets available." in snapshot, snapshot)
            assertTrue(snapshot.indexOf("[Full reset]") < snapshot.indexOf("[Dated reset]"), snapshot)
            assertTrue("Reset current usage limits." in snapshot, snapshot)
            assertTrue("Dated description" in snapshot, snapshot)
            assertTrue("Expiry unknown" in snapshot, snapshot)
            assertTrue("Expires 2026-11-01T01:02:03Z" in snapshot, snapshot)
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitSnapshot()
            assertEquals(1, vm.dismissals)
            assertTrue(vm.selections.isEmpty())
            assertTrue(vm.confirmations.isEmpty())
        }
    }

    test("picker keyboard action passes the exact specific ID, never an option or null") {
        val vm = RecordingReset(choices())
        runMosaicTest {
            setContentAndSnapshot { RenderRecording(vm) }
            // Cancel starts focused; Tab wraps to the first credit row.
            sendKeyEvent(KeyboardEvent(codepoint = 9))
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitSnapshot()
            assertEquals(listOf("credit"), vm.selections)
            assertEquals(0, vm.dismissals)
            assertTrue(vm.confirmations.isEmpty())
        }
    }

    test("legacy nullable option is visible but disabled and cannot request autochoice") {
        val vm = RecordingReset(UsageResetState.Choosing(UsageResetRequest(
            1, listOf(unknown.copy(creditId = null)),
        )))
        runMosaicTest {
            val snapshot = setContentAndSnapshot { RenderRecording(vm) }
            assertTrue("[Full reset]" in snapshot, snapshot)
            sendKeyEvent(KeyboardEvent(codepoint = 9))
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitSnapshot()
            assertTrue(vm.selections.isEmpty())
            assertTrue(vm.confirmations.isEmpty())
            assertEquals(1, vm.dismissals)
        }
    }

    for (option in listOf(unknown, dated)) {
        test("confirmation ${option.creditId} displays expiry and Go back starts focused") {
            val expected = UsageResetState.Confirming(option)
            val vm = RecordingReset(expected)
            runMosaicTest {
                val snapshot = setContentAndSnapshot { RenderRecording(vm) }
                assertTrue("Use this reset?" in snapshot, snapshot)
                assertTrue(option.title in snapshot, snapshot)
                assertTrue(option.description in snapshot, snapshot)
                assertTrue(
                    (option.expiresAt?.let { "Expires $it" } ?: "Expiry unknown") in snapshot,
                    snapshot,
                )
                assertTrue("[Go back] [Use reset]" in snapshot, snapshot)
                sendKeyEvent(KeyboardEvent(codepoint = 13))
                awaitSnapshot()
                assertEquals(1, vm.backs)
                assertTrue(vm.confirmations.isEmpty())
                assertEquals(0, vm.dismissals)
            }
        }
        test("confirmation ${option.creditId} passes the exact rendered object, not a copy") {
            val expected = UsageResetState.Confirming(option)
            val vm = RecordingReset(expected)
            runMosaicTest {
                setContentAndSnapshot { RenderRecording(vm) }
                sendKeyEvent(KeyboardEvent(codepoint = 9))
                sendKeyEvent(KeyboardEvent(codepoint = 13))
                awaitSnapshot()
                assertSame(expected, vm.confirmations.single())
                assertSame(option, vm.confirmations.single().option)
                assertEquals(0, vm.backs)
            }
        }
    }

    test("replacement confirmation callback captures replacement identity end-to-end") {
        val old = UsageResetState.Confirming(unknown)
        val next = UsageResetState.Confirming(dated)
        val vm = RecordingReset(old)
        runMosaicTest {
            setContentAndSnapshot { RenderRecording(vm) }
            vm.state.value = next
            awaitResetSnapshot("Dated reset")
            sendKeyEvent(KeyboardEvent(codepoint = 9))
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitSnapshot()
            assertSame(next, vm.confirmations.single())
            assertNotSame(old, vm.confirmations.single())
        }
    }

    test("confirmation Escape routes to fresh choices, never dismissal or confirmation") {
        val vm = RecordingReset(UsageResetState.Confirming(unknown))
        runMosaicTest {
            setContentAndSnapshot { RenderRecording(vm) }
            sendKeyEvent(KeyboardEvent(codepoint = 27))
            awaitSnapshot()
            assertEquals(1, vm.backs)
            assertEquals(0, vm.dismissals)
            assertTrue(vm.confirmations.isEmpty())
        }
    }

    test("Consuming renders progress only and Escape Enter and Tab cannot dispatch commands") {
        val vm = RecordingReset(UsageResetState.Consuming(unknown))
        var tick by mutableStateOf(0)
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Text("tick=$tick")
                RenderRecording(vm)
            }
            assertTrue("Resetting your usage…" in snapshot, snapshot)
            for (action in listOf("[Close]", "[Cancel]", "[Use reset]", "[Try again]", "[Refresh]")) {
                assertFalse(action in snapshot, snapshot)
            }
            for (key in listOf(27, 13, 9, 13)) sendKeyEvent(KeyboardEvent(codepoint = key))
            tick++
            awaitSnapshot()
            assertEquals(0, vm.events)
            assertTrue(vm.confirmations.isEmpty())
        }
    }

    test("unknown result warns without promising rollback or fresh usage and Close starts focused") {
        val vm = RecordingReset(UsageResetState.ConsumeFailed(unknown))
        runMosaicTest {
            val snapshot = setContentAndSnapshot { RenderRecording(vm) }
            assertTrue("The reset may have been used." in snapshot, snapshot)
            assertTrue("it may not be up to date" in snapshot, snapshot)
            assertTrue("[Close] [Try again]" in snapshot, snapshot)
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitSnapshot()
            assertEquals(1, vm.dismissals)
            assertEquals(0, vm.retries)
            assertTrue(vm.confirmations.isEmpty())
        }
    }

    test("unknown result Try again invokes fresh-selection command, not confirmation") {
        val vm = RecordingReset(UsageResetState.ConsumeFailed(unknown))
        runMosaicTest {
            setContentAndSnapshot { RenderRecording(vm) }
            sendKeyEvent(KeyboardEvent(codepoint = 9))
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitSnapshot()
            assertEquals(1, vm.retries)
            assertTrue(vm.confirmations.isEmpty())
            assertEquals(0, vm.refreshes)
        }
    }

    test("missing details requires specific reset and offers Close-first and explicit Refresh") {
        val vm = RecordingReset(UsageResetState.PreparationFailed)
        runMosaicTest {
            val snapshot = setContentAndSnapshot { RenderRecording(vm) }
            assertTrue("Reset details unavailable." in snapshot, snapshot)
            assertTrue("Select a specific reset" in snapshot, snapshot)
            assertTrue("Refresh usage and try again." in snapshot, snapshot)
            assertTrue("[Close] [Refresh]" in snapshot, snapshot)
            assertFalse("backend will choose" in snapshot, snapshot)
            assertFalse("[Use reset]" in snapshot, snapshot)
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitSnapshot()
            assertEquals(1, vm.dismissals)
            assertEquals(0, vm.refreshes)
        }
    }

    test("missing detail Refresh invokes only the usage refresh route") {
        val vm = RecordingReset(UsageResetState.PreparationFailed)
        runMosaicTest {
            setContentAndSnapshot { RenderRecording(vm) }
            sendKeyEvent(KeyboardEvent(codepoint = 9))
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitSnapshot()
            assertEquals(1, vm.refreshes)
            assertTrue(vm.selections.isEmpty())
            assertTrue(vm.confirmations.isEmpty())
        }
    }

    val outcomeMessages = mapOf(
        CodexRateLimitResetOutcome.Reset to "Usage reset.",
        CodexRateLimitResetOutcome.NothingToReset to "Your usage does not need a reset right now.",
        CodexRateLimitResetOutcome.NoCredit to "That reset is no longer available.",
        CodexRateLimitResetOutcome.AlreadyRedeemed to "This reset was already used successfully.",
    )
    for ((outcome, message) in outcomeMessages) {
        test("Completed $outcome preserves distinct result and Close starts focused") {
            val vm = RecordingReset(UsageResetState.Completed(outcome, true))
            runMosaicTest {
                val snapshot = setContentAndSnapshot { RenderRecording(vm) }
                assertTrue(message in snapshot, snapshot)
                assertTrue("[Close]" in snapshot, snapshot)
                assertFalse("[Try again]" in snapshot, snapshot)
                sendKeyEvent(KeyboardEvent(codepoint = 13))
                awaitSnapshot()
                assertEquals(1, vm.dismissals)
                assertTrue(vm.confirmations.isEmpty())
            }
        }
    }

    test("Completed retains legacy selectedCredit false rendering without new autochoice path") {
        val vm = RecordingReset(UsageResetState.Completed(CodexRateLimitResetOutcome.NoCredit, false))
        runMosaicTest {
            val snapshot = setContentAndSnapshot { RenderRecording(vm) }
            assertTrue("No usage limit resets are available." in snapshot, snapshot)
            assertFalse("That reset is no longer available." in snapshot, snapshot)
        }
    }

    for (state in listOf(
        choices(),
        UsageResetState.PreparationFailed,
        UsageResetState.ConsumeFailed(unknown),
        UsageResetState.Completed(CodexRateLimitResetOutcome.Reset, true),
    )) {
        test("Escape dismisses ${state::class.simpleName} without other commands") {
            val vm = RecordingReset(state)
            runMosaicTest {
                setContentAndSnapshot { RenderRecording(vm) }
                sendKeyEvent(KeyboardEvent(codepoint = 27))
                awaitSnapshot()
                assertEquals(1, vm.dismissals)
                assertEquals(1, vm.events)
                assertTrue(vm.confirmations.isEmpty())
            }
        }
    }

    test("real child owns choosing confirmation consume and result without extra refresh through full host") {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val finish = CompletableDeferred<Unit>()
        val consumed = mutableListOf<String>()
        var refreshes = 0
        val deps = object : UsageResetDependencies {
            override val usage = MutableStateFlow<SettingsAccountUsageState>(
                SettingsAccountUsageState.Available(CodexAccountUsageSnapshot(
                    rateLimits = emptyList(),
                    resetCredits = CodexRateLimitResetCredits(
                        1, listOf(CodexRateLimitResetCredit("credit", null, null)),
                    ),
                    fetchedAt = Instant.parse("2026-10-02T00:00:00Z"),
                )),
            )
            override suspend fun consume(creditId: String): CodexRateLimitResetOutcome {
                consumed += creditId
                finish.await()
                return CodexRateLimitResetOutcome.AlreadyRedeemed
            }
            override suspend fun refreshUsage() {
                refreshes++
                error("refresh failed")
            }
        }
        val vm = createUsageResetViewModel(deps, owner)
        try {
            vm.show()
            runMosaicTest {
                setContentAndSnapshot {
                    Box {
                        TuiPopupHost(Modifier.width(100).height(30)) { UsageResetDialogHost(vm) }
                    }
                }
                sendKeyEvent(KeyboardEvent(codepoint = 9))
                sendKeyEvent(KeyboardEvent(codepoint = 13))
                val confirming = awaitResetSnapshot("Use this reset?")
                assertTrue("Expiry unknown" in confirming, confirming)
                assertTrue(consumed.isEmpty())
                sendKeyEvent(KeyboardEvent(codepoint = 9))
                sendKeyEvent(KeyboardEvent(codepoint = 13))
                awaitResetSnapshot("Resetting your usage")
                assertEquals(listOf("credit"), consumed)
                vm.dismiss()
                assertIs<UsageResetState.Consuming>(vm.state.value)
                sendKeyEvent(KeyboardEvent(codepoint = 13))
                assertEquals(listOf("credit"), consumed)
                finish.complete(Unit)
                awaitResetSnapshot("This reset was already used successfully.")
                assertEquals(0, refreshes)
                assertEquals(
                    UsageResetState.Completed(CodexRateLimitResetOutcome.AlreadyRedeemed, true),
                    vm.state.value,
                )
                sendKeyEvent(KeyboardEvent(codepoint = 13))
                awaitSnapshot()
                assertEquals(UsageResetState.Hidden, vm.state.value)
                assertEquals(listOf("credit"), consumed)
            }
        } finally { finish.complete(Unit); vm.close(); owner.cancel() }
    }

    test("public state renderer passes captured exact confirmation to supplied callback") {
        val expected = UsageResetState.Confirming(unknown)
        var captured: UsageResetState.Confirming? = null
        var marker by mutableStateOf("pending")
        runMosaicTest {
            setContentAndSnapshot {
                Box {
                    TuiPopupHost(Modifier.width(100).height(30)) {
                        Text(marker)
                        UsageResetDialog(
                            state = expected,
                            onSelect = { error("not choosing") },
                            onConfirm = { captured = it; marker = "confirmed" },
                            onBack = {},
                            onRetry = { error("not failed") },
                            onRefresh = { error("not missing details") },
                            onDismiss = {},
                        )
                    }
                }
            }
            sendKeyEvent(KeyboardEvent(codepoint = 9))
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitSnapshot()
            assertSame(expected, captured)
        }
    }

    test("real child unknown result refreshes once; Try again selects fresh details without replay") {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val consumed = mutableListOf<String>()
        var refreshes = 0
        val deps = object : UsageResetDependencies {
            override val usage = MutableStateFlow<SettingsAccountUsageState>(
                SettingsAccountUsageState.Available(CodexAccountUsageSnapshot(
                    rateLimits = emptyList(),
                    resetCredits = CodexRateLimitResetCredits(
                        1, listOf(CodexRateLimitResetCredit("credit", null, null)),
                    ),
                    fetchedAt = Instant.parse("2026-10-02T00:00:00Z"),
                )),
            )
            override suspend fun consume(creditId: String): CodexRateLimitResetOutcome {
                consumed += creditId
                error("reply lost")
            }
            override suspend fun refreshUsage() {
                refreshes++
                val before = (usage.value as SettingsAccountUsageState.Available).snapshot
                usage.value = SettingsAccountUsageState.Available(before.copy(
                    resetCredits = CodexRateLimitResetCredits(
                        1, listOf(CodexRateLimitResetCredit("fresh", null, null, "Fresh reset")),
                    ),
                ))
                error("refresh response lost")
            }
        }
        val vm = createUsageResetViewModel(deps, owner)
        try {
            vm.show(); vm.select("credit")
            runMosaicTest {
                setContentAndSnapshot {
                    Box {
                        TuiPopupHost(Modifier.width(100).height(30)) { UsageResetDialogHost(vm) }
                    }
                }
                sendKeyEvent(KeyboardEvent(codepoint = 9))
                sendKeyEvent(KeyboardEvent(codepoint = 13))
                val failure = awaitResetSnapshot("The reset may have been used.")
                assertTrue("it may not be up to date" in failure, failure)
                assertEquals(listOf("credit"), consumed)
                assertEquals(1, refreshes)
                sendKeyEvent(KeyboardEvent(codepoint = 9))
                sendKeyEvent(KeyboardEvent(codepoint = 13))
                val choices = awaitResetSnapshot("[Fresh reset]")
                assertTrue("Expiry unknown" in choices, choices)
                assertEquals("fresh", assertIs<UsageResetState.Choosing>(vm.state.value).request.options.single().creditId)
                assertEquals(listOf("credit"), consumed)
                assertEquals(1, refreshes)
                // Cancel is focused again; it must not automatically select/consume.
                sendKeyEvent(KeyboardEvent(codepoint = 13))
                awaitSnapshot()
                assertEquals(UsageResetState.Hidden, vm.state.value)
                assertEquals(listOf("credit"), consumed)
            }
        } finally { vm.close(); owner.cancel() }
    }
}

private val unknown = UsageResetOption("credit", "Full reset", "Reset current usage limits.", null)
private val dated = UsageResetOption(
    "dated", "Dated reset", "Dated description", Instant.parse("2026-11-01T01:02:03Z"),
)
private fun choices(): UsageResetState.Choosing =
    UsageResetState.Choosing(UsageResetRequest(2, listOf(unknown, dated)))

@Composable
private fun RenderRecording(vm: RecordingReset) {
    Box {
        TuiPopupHost(Modifier.width(100).height(30)) {
            Text("events=${vm.events}")
            UsageResetDialogHost(vm)
        }
    }
}

private class RecordingReset(initial: UsageResetState) : UsageResetViewModel {
    override val state = MutableStateFlow(initial)
    val selections = mutableListOf<String>()
    val confirmations = mutableListOf<UsageResetState.Confirming>()
    var dismissals = 0
    var backs = 0
    var retries = 0
    var refreshes = 0
    var events by mutableStateOf(0)
    var closed = false
    override fun show() { events++ }
    override fun select(creditId: String) { selections += creditId; events++ }
    override fun confirm(expected: UsageResetState.Confirming) { confirmations += expected; events++ }
    override fun back() { backs++; events++ }
    override fun retry() { retries++; events++ }
    override fun refreshChoices() { refreshes++; events++ }
    override fun dismiss() { dismissals++; events++ }
    override fun close() { closed = true }
}

private suspend fun TestMosaic<String>.awaitResetSnapshot(expected: String): String {
    var snapshot = awaitSnapshot()
    repeat(12) {
        if (expected in snapshot) return snapshot
        snapshot = awaitSnapshot()
    }
    assertTrue(expected in snapshot, snapshot)
    return snapshot
}
