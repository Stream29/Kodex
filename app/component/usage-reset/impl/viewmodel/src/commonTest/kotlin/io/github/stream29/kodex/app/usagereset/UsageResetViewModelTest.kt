@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.app.usagereset

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.settings.contract.SettingsAccountUsageState
import io.github.stream29.kodex.app.settings.contract.UsageResetOption
import io.github.stream29.kodex.app.settings.contract.UsageResetRequest
import io.github.stream29.kodex.app.settings.contract.UsageResetState
import io.github.stream29.kodex.app.usagereset.contract.UsageResetDependencies
import io.github.stream29.kodex.app.usagereset.contract.UsageResetViewModel
import io.github.stream29.kodex.openai.accountusage.CodexAccountUsageSnapshot
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetCredit
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetCredits
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withContext
import kotlin.test.*
import kotlin.time.Instant

val usageResetViewModelTest by testSuite {
    test("construction is hidden without I/O; preserved DTO validation remains nonwire") {
        runTest {
            val deps = FakeDependencies()
            val vm = DefaultUsageResetViewModelFactory.create(deps, this)
            try {
                assertEquals(UsageResetState.Hidden, vm.state.value)
                assertTrue(deps.consumed.isEmpty())
                assertEquals(0, deps.refreshes)
                assertNull(UsageResetOption(null, "title", "description", null).creditId)
                assertFailsWith<IllegalArgumentException> { UsageResetOption("", "t", "d", null) }
                assertFailsWith<IllegalArgumentException> { UsageResetOption("id", "", "d", null) }
                assertFailsWith<IllegalArgumentException> { UsageResetOption("id", "t", "", null) }
                assertFailsWith<IllegalArgumentException> { UsageResetRequest(0, listOf(option())) }
                assertFailsWith<IllegalArgumentException> { UsageResetRequest(1, emptyList()) }
                assertFailsWith<IllegalArgumentException> {
                    UsageResetRequest(1, listOf(option(), option("other")))
                }
            } finally { vm.close() }
        }
    }

    for (count in listOf(null, 0L, 5L)) {
        for (details in listOf<List<CodexRateLimitResetCredit>?>(null, emptyList())) {
            test("count $count and details $details cannot choose or consume") {
                runTest {
                    val deps = FakeDependencies(usage(details, count))
                    val vm = createUsageResetViewModel(deps, this)
                    try {
                        vm.show()
                        assertEquals(UsageResetState.PreparationFailed, vm.state.value)
                        vm.select("credit")
                        vm.confirm(UsageResetState.Confirming(option()))
                        vm.back(); vm.retry()
                        runCurrent()
                        assertEquals(UsageResetState.PreparationFailed, vm.state.value)
                        assertTrue(deps.consumed.isEmpty())
                        assertEquals(0, deps.refreshes)
                    } finally { vm.close() }
                }
            }
        }
    }

    test("details preserve order and expiry; unknown or undersized count uses detail count") {
        runTest {
            val expiry = Instant.parse("2026-11-01T01:02:03Z")
            val details = listOf(
                CodexRateLimitResetCredit("second", null, expiry, "Special", "Custom description"),
                credit,
            )
            val deps = FakeDependencies()
            val vm = createUsageResetViewModel(deps, this)
            try {
                for (count in listOf(null, 0L, 1L, 9L)) {
                    deps.usage.value = usage(details, count)
                    vm.show()
                    val request = assertIs<UsageResetState.Choosing>(vm.state.value).request
                    assertEquals((count ?: 2L).coerceAtLeast(2L), request.availableCount)
                    assertEquals(listOf("second", "credit"), request.options.map { it.creditId })
                    assertEquals(expiry, request.options.first().expiresAt)
                    assertEquals("Special", request.options.first().title)
                    assertEquals("Custom description", request.options.first().description)
                    assertEquals(option(), request.options.last())
                    assertNull(request.options.last().expiresAt)
                }
                assertEquals(0, deps.refreshes)
            } finally { vm.close() }
        }
    }

    test("unavailable usage fails; same-account fallback may provide actual details") {
        runTest {
            val snapshot = snapshot(listOf(credit))
            val deps = FakeDependencies()
            val vm = createUsageResetViewModel(deps, this)
            try {
                for (state in listOf(
                    SettingsAccountUsageState.Available(snapshot),
                    SettingsAccountUsageState.Loading(snapshot),
                    SettingsAccountUsageState.Failed("failed", snapshot),
                    SettingsAccountUsageState.Redeeming(snapshot),
                )) {
                    deps.usage.value = state
                    vm.show()
                    assertEquals(option(), assertIs<UsageResetState.Choosing>(vm.state.value).request.options.single())
                }
                for (state in listOf(
                    SettingsAccountUsageState.Unavailable,
                    SettingsAccountUsageState.Loading(),
                    SettingsAccountUsageState.Failed("failed"),
                )) {
                    deps.usage.value = state
                    vm.show()
                    assertEquals(UsageResetState.PreparationFailed, vm.state.value)
                }
            } finally { vm.close() }
        }
    }

    test("blank IDs excluded, blank labels fall back, missing and duplicate IDs do not select") {
        runTest {
            val deps = FakeDependencies(usage(listOf(
                credit.copy(id = ""),
                credit.copy(title = "", description = " "),
                credit.copy(id = "duplicate"),
                credit.copy(id = "duplicate"),
            )))
            val vm = createUsageResetViewModel(deps, this)
            try {
                vm.select("credit")
                assertEquals(UsageResetState.Hidden, vm.state.value)
                vm.show()
                val choosing = assertIs<UsageResetState.Choosing>(vm.state.value)
                assertEquals(3, choosing.request.options.size)
                assertEquals(option(), choosing.request.options.first())
                for (id in listOf("", " ", "missing", "duplicate")) {
                    vm.select(id)
                    assertSame(choosing, vm.state.value)
                }
                vm.select("credit")
                val confirming = assertIs<UsageResetState.Confirming>(vm.state.value)
                vm.select("duplicate")
                assertSame(confirming, vm.state.value)
                vm.back()
                deps.usage.value = usage(listOf(credit.copy(id = "")))
                vm.show()
                assertEquals(UsageResetState.PreparationFailed, vm.state.value)
                assertTrue(deps.consumed.isEmpty())
            } finally { vm.close() }
        }
    }

    test("exact confirming accepted; copied equal confirmation and all duplicate calls rejected") {
        runTest {
            val deps = FakeDependencies()
            val vm = createUsageResetViewModel(deps, this)
            try {
                val confirming = vm.choose()
                vm.confirm(confirming.copy())
                assertSame(confirming, vm.state.value)
                assertTrue(deps.consumed.isEmpty())
                vm.confirm(confirming)
                assertIs<UsageResetState.Consuming>(vm.state.value)
                vm.confirm(confirming); vm.confirm(confirming.copy())
                runCurrent()
                assertEquals(listOf("credit"), deps.consumed)
                val result = assertIs<UsageResetState.Completed>(vm.state.value)
                assertEquals(CodexRateLimitResetOutcome.Reset, result.outcome)
                assertTrue(result.selectedCredit)
                vm.confirm(confirming)
                runCurrent()
                assertEquals(listOf("credit"), deps.consumed)
                assertEquals(0, deps.refreshes)
            } finally { vm.close() }
        }
    }

    test("back reads fresh details and invalidates even same-value old confirmation") {
        runTest {
            val deps = FakeDependencies()
            val vm = createUsageResetViewModel(deps, this)
            try {
                val old = vm.choose()
                vm.back()
                vm.select("credit")
                val next = assertIs<UsageResetState.Confirming>(vm.state.value)
                assertEquals(old, next)
                assertNotSame(old, next)
                vm.confirm(old)
                assertSame(next, vm.state.value)
                deps.usage.value = usage(listOf(credit.copy(id = "fresh")))
                vm.back()
                assertEquals("fresh", assertIs<UsageResetState.Choosing>(vm.state.value).request.options.single().creditId)
                vm.select("fresh")
                deps.usage.value = SettingsAccountUsageState.Unavailable
                vm.back()
                assertEquals(UsageResetState.PreparationFailed, vm.state.value)
                runCurrent()
                assertTrue(deps.consumed.isEmpty())
                assertEquals(0, deps.refreshes)
            } finally { vm.close() }
        }
    }

    test("dismiss and reopen invalidate stale confirmation without closing reusable child") {
        runTest {
            val deps = FakeDependencies()
            val vm = createUsageResetViewModel(deps, this)
            try {
                val old = vm.choose()
                vm.dismiss()
                assertEquals(UsageResetState.Hidden, vm.state.value)
                val next = vm.choose()
                vm.confirm(old)
                assertSame(next, vm.state.value)
                vm.confirm(next)
                runCurrent()
                assertEquals(listOf("credit"), deps.consumed)
            } finally { vm.close() }
        }
    }

    for (outcome in CodexRateLimitResetOutcome.entries) {
        for (refreshFails in listOf(false, true)) {
            test("$outcome remains exact with no extra refresh even if refresh would fail=$refreshFails") {
                runTest {
                    val deps = FakeDependencies().apply {
                        consumeAction = { outcome }
                        refreshAction = { if (refreshFails) error("refresh failed") }
                    }
                    val vm = createUsageResetViewModel(deps, this)
                    try {
                        vm.confirm(vm.choose())
                        runCurrent()
                        assertEquals(UsageResetState.Completed(outcome, true), vm.state.value)
                        assertEquals(listOf("credit"), deps.consumed)
                        assertEquals(0, deps.refreshes)
                        vm.retry(); vm.back()
                        assertEquals(UsageResetState.Completed(outcome, true), vm.state.value)
                        vm.dismiss()
                        assertEquals(UsageResetState.Hidden, vm.state.value)
                    } finally { vm.close() }
                }
            }
        }
    }

    for (refreshFails in listOf(false, true)) {
        test("unknown result refreshes once without replay; refresh failure=$refreshFails") {
            runTest {
                val order = mutableListOf<String>()
                val deps = FakeDependencies().apply {
                    consumeAction = { order += "consume"; error("reply lost") }
                    refreshAction = { order += "refresh"; if (refreshFails) error("refresh lost") }
                }
                val vm = createUsageResetViewModel(deps, this)
                try {
                    val confirming = vm.choose()
                    vm.confirm(confirming)
                    runCurrent()
                    assertEquals(listOf("consume", "refresh"), order)
                    assertEquals(UsageResetState.ConsumeFailed(option()), vm.state.value)
                    assertEquals(1, deps.refreshes)
                    vm.confirm(confirming)
                    assertEquals(listOf("credit"), deps.consumed)
                    deps.usage.value = usage(listOf(credit.copy(id = "fresh")))
                    vm.retry()
                    assertEquals("fresh", assertIs<UsageResetState.Choosing>(vm.state.value).request.options.single().creditId)
                    runCurrent()
                    assertEquals(listOf("credit"), deps.consumed)
                    assertEquals(1, deps.refreshes)
                } finally { vm.close() }
            }
        }
    }

    test("all commands including source/page dismissal ineffective during consume and refresh") {
        runTest {
            val finishConsume = CompletableDeferred<Unit>()
            val finishRefresh = CompletableDeferred<Unit>()
            val deps = FakeDependencies().apply {
                consumeAction = { finishConsume.await(); error("reply lost") }
                refreshAction = { finishRefresh.await() }
            }
            val vm = createUsageResetViewModel(deps, this)
            try {
                val expected = vm.choose()
                vm.confirm(expected)
                runCurrent()
                fun ignoredCommands() {
                    vm.show(); vm.select("credit"); vm.confirm(expected)
                    vm.back(); vm.retry(); vm.refreshChoices(); vm.dismiss()
                }
                ignoredCommands()
                assertIs<UsageResetState.Consuming>(vm.state.value)
                deps.usage.value = SettingsAccountUsageState.Unavailable
                vm.dismiss()
                assertIs<UsageResetState.Consuming>(vm.state.value)
                finishConsume.complete(Unit)
                runCurrent()
                val failed = assertIs<UsageResetState.ConsumeFailed>(vm.state.value)
                ignoredCommands()
                assertSame(failed, vm.state.value)
                assertEquals(listOf("credit"), deps.consumed)
                assertEquals(1, deps.refreshes)
                finishRefresh.complete(Unit)
                runCurrent()
                vm.dismiss()
                assertEquals(UsageResetState.Hidden, vm.state.value)
            } finally { vm.close() }
        }
    }

    test("explicit missing-detail refresh is once and reconstructs only actual details") {
        runTest {
            val finish = CompletableDeferred<Unit>()
            val deps = FakeDependencies(usage(null, 10)).apply {
                refreshAction = { finish.await(); usage.value = usage(listOf(credit)) }
            }
            val vm = createUsageResetViewModel(deps, this)
            try {
                vm.refreshChoices()
                assertEquals(0, deps.refreshes)
                vm.show()
                vm.refreshChoices(); vm.refreshChoices()
                runCurrent()
                assertEquals(1, deps.refreshes)
                assertEquals(UsageResetState.PreparationFailed, vm.state.value)
                finish.complete(Unit)
                runCurrent()
                assertIs<UsageResetState.Choosing>(vm.state.value)
                assertTrue(deps.consumed.isEmpty())
            } finally { vm.close() }
        }
    }

    test("refresh cannot claim fresh details on failure or count-only Unit success") {
        runTest {
            val deps = FakeDependencies(usage(null, 10))
            val vm = createUsageResetViewModel(deps, this)
            try {
                vm.show(); vm.refreshChoices()
                runCurrent()
                assertEquals(UsageResetState.PreparationFailed, vm.state.value)
                deps.refreshAction = {
                    deps.usage.value = usage(listOf(credit))
                    error("refresh failed")
                }
                vm.refreshChoices()
                runCurrent()
                assertEquals(UsageResetState.PreparationFailed, vm.state.value)
                assertEquals(2, deps.refreshes)
                assertTrue(deps.consumed.isEmpty())
            } finally { vm.close() }
        }
    }

    for (reopen in listOf(false, true)) {
        test("dismiss invalidates in-flight choice refresh even if reopened=$reopen") {
            runTest {
                val finish = CompletableDeferred<Unit>()
                val deps = FakeDependencies(usage(null, 10)).apply {
                    refreshAction = { finish.await() }
                }
                val vm = createUsageResetViewModel(deps, this)
                try {
                    vm.show(); vm.refreshChoices()
                    runCurrent()
                    vm.dismiss()
                    if (reopen) vm.show()
                    deps.usage.value = usage(listOf(credit))
                    finish.complete(Unit)
                    runCurrent()
                    assertEquals(
                        if (reopen) UsageResetState.PreparationFailed else UsageResetState.Hidden,
                        vm.state.value,
                    )
                    assertTrue(deps.consumed.isEmpty())
                } finally { vm.close() }
            }
        }
    }

    test("dependency command cancellation is not ordinary failure or automatic refresh") {
        runTest {
            val deps = FakeDependencies().apply {
                consumeAction = { throw CancellationException("cancelled waiting") }
            }
            val vm = createUsageResetViewModel(deps, this)
            try {
                vm.confirm(vm.choose())
                runCurrent()
                assertIs<UsageResetState.Consuming>(vm.state.value)
                assertEquals(0, deps.refreshes)
                assertEquals(listOf("credit"), deps.consumed)
                vm.dismiss()
                assertEquals(UsageResetState.Hidden, vm.state.value)
                vm.show()
                assertIs<UsageResetState.Choosing>(vm.state.value)
                assertEquals(listOf("credit"), deps.consumed)
            } finally { vm.close() }
        }
    }

    test("refresh cancellation preserves unknown failure without another consume or refresh") {
        runTest {
            val deps = FakeDependencies().apply {
                consumeAction = { error("reply lost") }
                refreshAction = { throw CancellationException("refresh cancelled") }
            }
            val vm = createUsageResetViewModel(deps, this)
            try {
                vm.confirm(vm.choose())
                runCurrent()
                assertEquals(UsageResetState.ConsumeFailed(option()), vm.state.value)
                assertEquals(listOf("credit"), deps.consumed)
                assertEquals(1, deps.refreshes)
                vm.dismiss()
                assertEquals(UsageResetState.Hidden, vm.state.value)
            } finally { vm.close() }
        }
    }

    test("choice refresh cancellation retains missing-detail failure and can be explicitly retried") {
        runTest {
            val deps = FakeDependencies(usage(null)).apply {
                refreshAction = { throw CancellationException("refresh cancelled") }
            }
            val vm = createUsageResetViewModel(deps, this)
            try {
                vm.show(); vm.refreshChoices()
                runCurrent()
                assertEquals(UsageResetState.PreparationFailed, vm.state.value)
                assertEquals(1, deps.refreshes)
                deps.refreshAction = { deps.usage.value = usage(listOf(credit)) }
                vm.refreshChoices()
                runCurrent()
                assertIs<UsageResetState.Choosing>(vm.state.value)
                assertEquals(2, deps.refreshes)
                assertTrue(deps.consumed.isEmpty())
            } finally { vm.close() }
        }
    }

    test("close cancels cooperative waiting and rejects every later command, not shared owner") {
        runTest {
            val never = CompletableDeferred<Unit>()
            var cancelled = false
            val deps = FakeDependencies().apply {
                consumeAction = {
                    try { never.await(); CodexRateLimitResetOutcome.Reset }
                    finally { cancelled = true }
                }
            }
            val vm = createUsageResetViewModel(deps, this)
            val confirming = vm.choose()
            vm.confirm(confirming)
            runCurrent()
            vm.close(); vm.close()
            assertEquals(UsageResetState.Hidden, vm.state.value)
            runCurrent()
            assertTrue(cancelled)
            vm.show(); vm.select("credit"); vm.confirm(confirming)
            vm.back(); vm.retry(); vm.refreshChoices(); vm.dismiss()
            runCurrent()
            assertEquals(UsageResetState.Hidden, vm.state.value)
            assertEquals(listOf("credit"), deps.consumed)
            assertEquals(0, deps.refreshes)
            assertTrue(coroutineContext[Job]!!.isActive)
            // Dependency/store lifetime was not disposed by closing the child.
            deps.usage.value = usage(null, 0)
        }
    }

    for (lateFailure in listOf(false, true)) {
        test("close blocks noncooperative late consume response failure=$lateFailure") {
            runTest {
                val finish = CompletableDeferred<Unit>()
                var remoteAccepted = false
                val deps = FakeDependencies().apply {
                    consumeAction = {
                        withContext(NonCancellable) {
                            finish.await()
                            remoteAccepted = true
                            if (lateFailure) error("late lost reply")
                            CodexRateLimitResetOutcome.Reset
                        }
                    }
                }
                val vm = createUsageResetViewModel(deps, this)
                val observed = mutableListOf<UsageResetState>()
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    vm.state.collect { observed += it }
                }
                try {
                    vm.confirm(vm.choose())
                    runCurrent()
                    vm.close()
                    assertEquals(UsageResetState.Hidden, vm.state.value)
                    val observationsAtClose = observed.size
                    finish.complete(Unit)
                    runCurrent()
                    assertTrue(remoteAccepted) // Hiding never promised backend rollback.
                    assertEquals(UsageResetState.Hidden, vm.state.value)
                    assertTrue(observed.drop(observationsAtClose).isEmpty(), observed.toString())
                    assertEquals(0, deps.refreshes)
                    assertEquals(listOf("credit"), deps.consumed)
                } finally { finish.complete(Unit); vm.close() }
            }
        }
    }

    test("late noncooperative refresh cannot restore failure or choices after close") {
        runTest {
            for (preparation in listOf(false, true)) {
                val finish = CompletableDeferred<Unit>()
                val deps = FakeDependencies(if (preparation) usage(null) else usage(listOf(credit))).apply {
                    consumeAction = { error("reply lost") }
                    refreshAction = {
                        withContext(NonCancellable) { finish.await() }
                        usage.value = usage(listOf(credit))
                    }
                }
                val vm = createUsageResetViewModel(deps, this)
                try {
                    if (preparation) { vm.show(); vm.refreshChoices() }
                    else vm.confirm(vm.choose())
                    runCurrent()
                    assertEquals(1, deps.refreshes)
                    vm.close()
                    finish.complete(Unit)
                    runCurrent()
                    assertEquals(UsageResetState.Hidden, vm.state.value)
                } finally { finish.complete(Unit); vm.close() }
            }
        }
    }

    test("parent owner cancellation hides child and prevents noncooperative result publication") {
        runTest {
            val parent = Job(coroutineContext[Job])
            val scope = CoroutineScope(coroutineContext + parent)
            val finish = CompletableDeferred<Unit>()
            val deps = FakeDependencies().apply {
                consumeAction = {
                    withContext(NonCancellable) { finish.await() }
                    CodexRateLimitResetOutcome.Reset
                }
            }
            val vm = createUsageResetViewModel(deps, scope)
            try {
                vm.confirm(vm.choose())
                runCurrent()
                scope.cancel()
                vm.show()
                finish.complete(Unit)
                runCurrent()
                assertEquals(UsageResetState.Hidden, vm.state.value)
                assertEquals(0, deps.refreshes)
            } finally { finish.complete(Unit); vm.close(); parent.cancel() }
        }
    }

    test("close before dispatched consume prevents dependency call") {
        runTest {
            val deps = FakeDependencies()
            val vm = createUsageResetViewModel(deps, this)
            vm.confirm(vm.choose())
            vm.close()
            runCurrent()
            assertTrue(deps.consumed.isEmpty())
            assertEquals(0, deps.refreshes)
            assertEquals(UsageResetState.Hidden, vm.state.value)
        }
    }

    test("caller job does not own admitted command; no account identity is captured at confirmation") {
        runTest {
            var currentBackendAccount = "before"
            var admittedAccount: String? = null
            val deps = FakeDependencies().apply {
                consumeAction = {
                    admittedAccount = currentBackendAccount
                    CodexRateLimitResetOutcome.Reset
                }
            }
            val vm = createUsageResetViewModel(deps, this)
            try {
                val confirming = vm.choose()
                val caller = launch {
                    vm.confirm(confirming)
                    currentBackendAccount = "at command start"
                    cancel()
                }
                runCurrent()
                caller.join()
                assertEquals("at command start", admittedAccount)
                assertEquals(listOf("credit"), deps.consumed)
                assertIs<UsageResetState.Completed>(vm.state.value)
            } finally { vm.close() }
        }
    }
}

private val credit = CodexRateLimitResetCredit("credit", null, null)
private val fetchedAt = Instant.parse("2026-10-02T00:00:00Z")
private fun option(id: String = "credit"): UsageResetOption =
    UsageResetOption(id, "Full reset", "Reset current usage limits.", null)

private fun snapshot(
    details: List<CodexRateLimitResetCredit>?,
    count: Long? = 1,
): CodexAccountUsageSnapshot = CodexAccountUsageSnapshot(
    rateLimits = emptyList(),
    resetCredits = CodexRateLimitResetCredits(count, details),
    fetchedAt = fetchedAt,
)

private fun usage(
    details: List<CodexRateLimitResetCredit>?,
    count: Long? = 1,
): SettingsAccountUsageState = SettingsAccountUsageState.Available(snapshot(details, count))

private class FakeDependencies(
    initial: SettingsAccountUsageState = usage(listOf(credit)),
) : UsageResetDependencies {
    override val usage = MutableStateFlow(initial)
    val consumed = mutableListOf<String>()
    var refreshes = 0
    var consumeAction: suspend (String) -> CodexRateLimitResetOutcome = { CodexRateLimitResetOutcome.Reset }
    var refreshAction: suspend () -> Unit = {}
    override suspend fun consume(creditId: String): CodexRateLimitResetOutcome {
        consumed += creditId
        return consumeAction(creditId)
    }
    override suspend fun refreshUsage() {
        refreshes++
        refreshAction()
    }
}

private fun UsageResetViewModel.choose(): UsageResetState.Confirming {
    show()
    select("credit")
    return assertIs(state.value)
}
