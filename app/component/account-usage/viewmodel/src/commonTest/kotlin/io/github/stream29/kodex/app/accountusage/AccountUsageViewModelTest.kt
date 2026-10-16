@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.app.accountusage

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.settings.contract.SettingsAccountUsageState
import io.github.stream29.kodex.app.settings.contract.snapshotOrNull
import io.github.stream29.kodex.openai.accountusage.CodexAccountTokenUsage
import io.github.stream29.kodex.openai.accountusage.CodexAccountUsageSnapshot
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetCredit
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetCredits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Instant

val accountUsageViewModelTest by testSuite {
    val snapshot = usageSnapshot()
    val cases = listOf(
        UsageCase(SettingsAccountUsageState.Unavailable, null, false, false, false),
        UsageCase(SettingsAccountUsageState.Loading(), null, true, false, false),
        UsageCase(SettingsAccountUsageState.Loading(snapshot), snapshot, true, false, false),
        UsageCase(SettingsAccountUsageState.Available(snapshot), snapshot, true, true, true),
        UsageCase(SettingsAccountUsageState.Failed("Safe failure"), null, true, true, false),
        UsageCase(SettingsAccountUsageState.Failed("Safe failure", snapshot), snapshot, true, true, false),
        UsageCase(SettingsAccountUsageState.Redeeming(snapshot), snapshot, true, false, false),
    )
    for (case in cases) {
        test("projects ${case.usage} without constructor refresh or synthesized state") {
            runTest {
                val ports = UsagePorts(case.usage)
                val vm = createAccountUsageViewModel(ports, backgroundScope)
                assertSame(case.usage, vm.state.value.usage)
                assertSame(case.snapshot, vm.state.value.usage.snapshotOrNull())
                assertEquals(case.visible, vm.state.value.actionsVisible)
                assertEquals(case.refresh, vm.state.value.refreshEnabled)
                assertEquals(case.reset, vm.state.value.resetEnabled)
                runCurrent()
                assertEquals(0, ports.refreshes)
                assertEquals(0, ports.resets)
                vm.close()
                assertFalse(vm.state.value.actionsVisible)
                assertFalse(vm.state.value.refreshEnabled)
                assertFalse(vm.state.value.resetEnabled)
            }
        }
    }
    test("source replacement does not retain another account fallback or infer unavailable as zero") {
        runTest {
            val old = usageSnapshot(tokens = 1_234)
            val ports = UsagePorts(SettingsAccountUsageState.Available(old))
            val vm = createAccountUsageViewModel(ports, backgroundScope)
            runCurrent()
            ports.usage.value = SettingsAccountUsageState.Loading(old)
            runCurrent()
            assertSame(old, vm.state.value.usage.snapshotOrNull())
            ports.usage.value = SettingsAccountUsageState.Unavailable
            runCurrent()
            assertNull(vm.state.value.usage.snapshotOrNull())
            ports.usage.value = SettingsAccountUsageState.Loading()
            runCurrent()
            assertNull(vm.state.value.usage.snapshotOrNull())
            val next = usageSnapshot(tokens = 0, count = null, credits = null)
            ports.usage.value = SettingsAccountUsageState.Available(next)
            runCurrent()
            assertSame(next, vm.state.value.usage.snapshotOrNull())
            assertNull(next.resetCredits.availableCount)
            assertNull(next.resetCredits.credits)
            assertEquals(0L, next.tokenUsage?.lifetimeTokens)
            assertEquals(0, ports.refreshes)
            vm.close()
        }
    }
    val credit = CodexRateLimitResetCredit("credit", grantedAt = null, expiresAt = null)
    for ((count, details, enabled) in listOf(
        Triple(null, null, false),
        Triple(0L, null, false),
        Triple(null, emptyList<CodexRateLimitResetCredit>(), false),
        Triple(0L, emptyList<CodexRateLimitResetCredit>(), false),
        Triple(2L, null, true),
        Triple(2L, emptyList<CodexRateLimitResetCredit>(), true),
        Triple(null, listOf(credit), true),
        Triple(0L, listOf(credit), true),
    )) {
        test("possible reset count=$count details=$details preserves nullable values") {
            val source = usageSnapshot(count = count, credits = details)
            val state = AccountUsageState(SettingsAccountUsageState.Available(source))
            assertEquals(enabled, state.resetEnabled)
            assertEquals(count, source.resetCredits.availableCount)
            assertEquals(details, source.resetCredits.credits)
        }
    }
    for (case in cases) {
        test("explicit refresh calls in ${case.usage} are not globally deduplicated by UI state") {
            runTest {
                val ports = UsagePorts(case.usage)
                val gate = CompletableDeferred<Unit>()
                ports.refreshBody = { gate.await() }
                val vm = createAccountUsageViewModel(ports, backgroundScope)
                vm.refresh()
                vm.refresh()
                runCurrent()
                assertEquals(2, ports.refreshes)
                assertSame(case.usage, vm.state.value.usage)
                gate.complete(Unit)
                runCurrent()
                // Unit completion does not manufacture Available or even an updated snapshot.
                assertSame(case.usage, vm.state.value.usage)
                vm.close()
            }
        }
    }
    test("reset is one-way intent only including count-only or unavailable programmatic calls") {
        runTest {
            val ports = UsagePorts(SettingsAccountUsageState.Unavailable)
            val vm = createAccountUsageViewModel(ports, backgroundScope)
            vm.requestReset()
            ports.usage.value = SettingsAccountUsageState.Available(usageSnapshot(count = 2, credits = null))
            runCurrent()
            vm.requestReset()
            vm.requestReset()
            assertEquals(3, ports.resets)
            assertEquals(0, ports.refreshes)
            assertTrue(vm.state.value.usage is SettingsAccountUsageState.Available)
            vm.close()
            vm.requestReset()
            assertEquals(3, ports.resets)
        }
    }
    test("command errors report to shared authority while source Failed stays source-owned") {
        runTest {
            val sourceFailed = SettingsAccountUsageState.Failed("Safe source failure", snapshot)
            val ports = UsagePorts(sourceFailed)
            val failure = IllegalStateException("do not render raw diagnostic")
            ports.refreshBody = { throw failure }
            val vm = createAccountUsageViewModel(ports, backgroundScope)
            vm.refresh()
            runCurrent()
            assertEquals<List<Throwable>>(listOf(failure), ports.failures)
            assertTrue(vm.state.value.operationFailure)
            assertSame(sourceFailed, vm.state.value.usage)
            vm.dismissFailure()
            runCurrent()
            assertFalse(vm.state.value.operationFailure)
            assertSame(sourceFailed, vm.state.value.usage)
            assertEquals(1, ports.refreshes)
            assertEquals(1, ports.dismissals)
            vm.close()
        }
    }
    test("normal refresh may publish Failed without throwing or reporting a separate failure") {
        runTest {
            val ports = UsagePorts(SettingsAccountUsageState.Loading())
            ports.refreshBody = { ports.usage.value = SettingsAccountUsageState.Failed("Safe supplier failure") }
            val vm = createAccountUsageViewModel(ports, backgroundScope)
            vm.refresh()
            runCurrent()
            assertTrue(vm.state.value.usage is SettingsAccountUsageState.Failed)
            assertTrue(ports.failures.isEmpty())
            vm.close()
        }
    }
    test("refresh cancellation is unreported and synchronous reset cancellation propagates") {
        runTest {
            val ports = UsagePorts(SettingsAccountUsageState.Available(snapshot))
            ports.refreshBody = { throw CancellationException("cancel") }
            val vm = createAccountUsageViewModel(ports, backgroundScope)
            vm.refresh()
            runCurrent()
            assertTrue(ports.failures.isEmpty())
            ports.resetError = IllegalArgumentException("private diagnostic")
            vm.requestReset()
            assertEquals(listOf(ports.resetError), ports.failures)
            ports.resetError = CancellationException("cancel reset intent")
            assertFailsWith<CancellationException> { vm.requestReset() }
            assertEquals(1, ports.failures.size)
            vm.close()
        }
    }
    test("close cancels waits and observers not shared source parent or reset owner") {
        runTest {
            val ports = UsagePorts(SettingsAccountUsageState.Available(snapshot))
            val gate = CompletableDeferred<Unit>()
            var cancelled = false
            ports.refreshBody = { try { gate.await() } finally { cancelled = true } }
            val parent = Job()
            val vm = createAccountUsageViewModel(ports, CoroutineScope(coroutineContext + parent))
            vm.requestReset()
            vm.refresh()
            runCurrent()
            vm.close()
            vm.close()
            runCurrent()
            assertTrue(cancelled)
            assertTrue(parent.isActive)
            val closed = vm.state.value
            assertTrue(closed.closed)
            ports.usage.value = SettingsAccountUsageState.Unavailable
            ports.operationFailure.value = true
            vm.refresh()
            vm.requestReset()
            vm.dismissFailure()
            runCurrent()
            assertEquals(closed, vm.state.value)
            assertEquals(1, ports.refreshes)
            assertEquals(1, ports.resets)
            assertEquals(0, ports.dismissals)
            assertTrue(ports.failures.isEmpty())
            parent.cancel()
        }
    }
    test("owner cancellation and inactive owner reject new commands") {
        runTest {
            val parent = Job()
            val ports = UsagePorts()
            val vm = createAccountUsageViewModel(ports, CoroutineScope(coroutineContext + parent))
            parent.cancel()
            runCurrent()
            assertTrue(vm.state.value.closed)
            val next = createAccountUsageViewModel(ports, CoroutineScope(coroutineContext + parent))
            assertTrue(next.state.value.closed)
            next.refresh()
            next.requestReset()
            runCurrent()
            assertEquals(0, ports.refreshes)
            assertEquals(0, ports.resets)
        }
    }
}

private data class UsageCase(
    val usage: SettingsAccountUsageState,
    val snapshot: CodexAccountUsageSnapshot?,
    val visible: Boolean,
    val refresh: Boolean,
    val reset: Boolean,
)

private fun usageSnapshot(
    tokens: Long? = 123_456,
    count: Long? = 2,
    credits: List<CodexRateLimitResetCredit>? = null,
): CodexAccountUsageSnapshot = CodexAccountUsageSnapshot(
    rateLimits = emptyList(),
    resetCredits = CodexRateLimitResetCredits(count, credits),
    tokenUsage = CodexAccountTokenUsage(lifetimeTokens = tokens),
    fetchedAt = Instant.parse("2026-10-02T00:00:00Z"),
)

private class UsagePorts(
    initial: SettingsAccountUsageState = SettingsAccountUsageState.Unavailable,
) : AccountUsageDependencies {
    override val usage = MutableStateFlow(initial)
    override val operationFailure = MutableStateFlow(false)
    var refreshes = 0
    var resets = 0
    var dismissals = 0
    var refreshBody: suspend () -> Unit = {}
    var resetError: Throwable? = null
    val failures = mutableListOf<Throwable>()
    override suspend fun refresh() { refreshes++; refreshBody() }
    override fun requestReset() { resetError?.let { throw it }; resets++ }
    override fun reportFailure(failure: Throwable) { failures += failure; operationFailure.value = true }
    override fun dismissFailure() { dismissals++; operationFailure.value = false }
}
