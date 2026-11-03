@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.rpc.browser

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.backend.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

private val leaseCapabilities = FrontendHostCapabilities(
    FrontendCapabilityStatus.Ready, FrontendCapabilityStatus.Ready,
    FrontendCapabilityStatus.Ready, FrontendCapabilityStatus.Ready,
)
private fun renewed(capabilities: FrontendHostCapabilities = leaseCapabilities) =
    LocalFrontendHostReply.OwnerRenewed(LocalFrontendHostOwnerLeaseMillis, capabilities)

private class LeaseRun(
    val job: Job,
    val ended: CompletableDeferred<Throwable>,
    val capabilities: MutableStateFlow<FrontendHostCapabilities>,
    val calls: MutableList<Long>,
)

private fun TestScope.startLease(
    initialRequestStarted: Long = 0,
    call: suspend (Int) -> LocalFrontendHostReply,
): LeaseRun {
    val capabilities = MutableStateFlow(leaseCapabilities)
    val ended = CompletableDeferred<Throwable>()
    val calls = mutableListOf<Long>()
    val lease = FrontendOwnerLease(initialRequestStarted, 60_000, capabilities) { testScheduler.currentTime }
    val job = launch {
        try {
            lease.maintain { request ->
                assertSame(LocalFrontendHostRequest.RenewOwner, request)
                calls += testScheduler.currentTime
                call(calls.size)
            }
        } catch (failure: Throwable) { ended.complete(failure) }
    }
    return LeaseRun(job, ended, capabilities, calls)
}

internal val frontendOwnerLeaseTest by testSuite {
    test("production-shaped renewal child keeps one business owner on short failure and ends it only at expiry") {
        runTest {
            var applicationStarts = 0
            var applicationCloses = 0
            var draft = ""
            val state = MutableStateFlow(leaseCapabilities)
            val application = async {
                runCatching {
                    coroutineScope {
                        val lease = FrontendOwnerLease(0, 60_000, state) { currentTime }
                        launch { lease.maintain { throw FrontendHostTransientFailure() } }
                        applicationStarts++
                        draft = "unsubmitted"
                        try { awaitCancellation() }
                        finally { applicationCloses++ }
                    }
                }.exceptionOrNull()
            }
            advanceTimeBy(21_000); runCurrent()
            assertEquals(1, applicationStarts)
            assertEquals(0, applicationCloses)
            assertEquals("unsubmitted", draft)
            assertTrue(application.isActive)
            advanceTimeBy(39_000); runCurrent()
            assertIs<IllegalStateException>(application.await())
            assertEquals(1, applicationStarts)
            assertEquals(1, applicationCloses)
        }
    }
    test("one lost renewal preserves the application scope and business capabilities until exact-owner recovery") {
        runTest {
            val draft = Any()
            val retainedDraft = draft
            val application = launch { awaitCancellation() }
            val run = startLease {
                if (it == 1) throw FrontendHostTransientFailure()
                renewed()
            }
            advanceTimeBy(20_000); runCurrent()
            assertEquals(listOf(20_000L), run.calls)
            assertTrue(application.isActive)
            assertFalse(run.ended.isCompleted)
            assertSame(retainedDraft, draft)
            assertEquals(leaseCapabilities.copy(
                oauthCallbacks = FrontendCapabilityStatus.Negotiating,
                frontendErrorObservation = FrontendCapabilityStatus.Negotiating,
            ), run.capabilities.value)
            advanceTimeBy(1_000); runCurrent()
            assertEquals(listOf(20_000L, 21_000L), run.calls)
            assertEquals(leaseCapabilities, run.capabilities.value)
            assertTrue(application.isActive)
            assertFalse(run.ended.isCompleted)
            run.job.cancelAndJoin()
            application.cancelAndJoin()
        }
    }
    test("perpetual transport failures stop at the old confirmed deadline without a request at expiry") {
        runTest {
            val run = startLease { throw FrontendHostTransientFailure() }
            advanceTimeBy(59_999); runCurrent()
            assertFalse(run.ended.isCompleted)
            assertEquals(40, run.calls.size)
            advanceTimeBy(1); runCurrent()
            assertIs<IllegalStateException>(run.ended.await())
            assertEquals(59_000L, run.calls.last())
            assertEquals(60_000L, currentTime)
            assertTrue(run.job.isCompleted)
        }
    }
    test("a renewal timeout is cancellable and retryable without waiting real lease time") {
        runTest {
            var cancelledRequests = 0
            val run = startLease {
                if (it == 1) {
                    try { awaitCancellation() }
                    finally { cancelledRequests++ }
                }
                renewed()
            }
            advanceTimeBy(30_000); runCurrent()
            assertEquals(1, cancelledRequests)
            assertFalse(run.ended.isCompleted)
            assertEquals(FrontendCapabilityStatus.Negotiating, run.capabilities.value.oauthCallbacks)
            advanceTimeBy(1_000); runCurrent()
            assertEquals(listOf(20_000L, 31_000L), run.calls)
            assertEquals(leaseCapabilities, run.capabilities.value)
            run.job.cancelAndJoin()
        }
    }
    test("slow bootstrap or pairing uses its request start and clips a hanging renew to the remaining lease") {
        runTest {
            advanceTimeBy(55_000)
            var requestCleaned = false
            val run = startLease {
                try { awaitCancellation() }
                finally { requestCleaned = true }
            }
            runCurrent()
            assertEquals(listOf(55_000L), run.calls)
            advanceTimeBy(5_000); runCurrent()
            assertTrue(requestCleaned)
            assertTrue(run.ended.isCompleted)
            assertEquals(60_000L, currentTime)
            assertEquals(1, run.calls.size)
            assertFailsWith<IllegalStateException> {
                FrontendOwnerLease(0, 60_000, MutableStateFlow(leaseCapabilities)) { currentTime }
            }
        }
    }
    test("successful renewal confirms request-start plus lease and never response-time plus lease") {
        runTest {
            val run = startLease {
                if (it == 1) { delay(9_000); renewed() }
                else throw FrontendHostTransientFailure()
            }
            advanceTimeBy(79_999); runCurrent()
            assertFalse(run.ended.isCompleted)
            advanceTimeBy(1); runCurrent()
            assertTrue(run.ended.isCompleted)
            assertEquals(80_000L, currentTime)
            assertEquals(40_000L, run.calls[1])
            assertTrue(run.calls.all { it < 80_000 })
        }
    }
    test("a late valid reply cannot revive an owner after a scheduler pause crosses the confirmed deadline") {
        runTest {
            var now = 0L
            val state = MutableStateFlow(leaseCapabilities)
            val lease = FrontendOwnerLease(0, 60_000, state) { now }
            var calls = 0
            assertFailsWith<IllegalStateException> {
                lease.maintain {
                    calls++
                    now = 60_001
                    renewed()
                }
            }
            assertEquals(1, calls)
            assertEquals(leaseCapabilities, state.value)
        }
    }
    test("known rejection typed closure offline bad reply and bad lease are terminal without retries") {
        runTest {
            val replies = listOf(
                LocalFrontendHostReply.OwnerClosed,
                LocalFrontendHostReply.Failure(LocalFrontendHostFailure.OwnerClosed),
                LocalFrontendHostReply.Failure(LocalFrontendHostFailure.BackendOffline),
                LocalFrontendHostReply.Failure(LocalFrontendHostFailure.Unauthorized),
                LocalFrontendHostReply.Failure(LocalFrontendHostFailure.IncompatibleProtocol),
                LocalFrontendHostReply.Failure(LocalFrontendHostFailure.ServiceFailed),
                LocalFrontendHostReply.ErrorObserved(LocalErrorObservationAdmission.Admitted),
                LocalFrontendHostReply.OwnerRenewed(60_001, leaseCapabilities),
                renewed(leaseCapabilities.copy(oauthCallbacks = FrontendCapabilityStatus.OwnerClosed)),
                renewed(leaseCapabilities.copy(frontendErrorObservation = FrontendCapabilityStatus.BackendOffline)),
            )
            for (reply in replies) {
                val run = startLease(initialRequestStarted = currentTime) { reply }
                advanceTimeBy(20_000); runCurrent()
                assertIs<FrontendHostRejected>(run.ended.await())
                assertEquals(1, run.calls.size)
                assertTrue(run.job.isCompleted)
            }
            for (failure in listOf(FrontendHostRejected(), FrontendHostInvalidResponse(), IllegalArgumentException())) {
                val run = startLease(initialRequestStarted = currentTime) { throw failure }
                advanceTimeBy(20_000); runCurrent()
                assertSame(failure, run.ended.await())
                assertEquals(1, run.calls.size)
            }
        }
    }
    test("caller cancellation aborts pending renewal and cannot become a transient retry") {
        runTest {
            var cleaned = false
            val run = startLease {
                try { awaitCancellation() }
                finally { cleaned = true }
            }
            advanceTimeBy(20_000); runCurrent()
            run.job.cancelAndJoin()
            assertTrue(cleaned)
            assertIs<CancellationException>(run.ended.await())
            advanceTimeBy(60_000); runCurrent()
            assertEquals(listOf(20_000L), run.calls)
            assertEquals(leaseCapabilities, run.capabilities.value)
        }
    }
    test("initial closed offline expired or incompatible leases are rejected before any renew") {
        for (status in listOf(FrontendCapabilityStatus.OwnerClosed, FrontendCapabilityStatus.BackendOffline)) {
            assertFailsWith<FrontendHostRejected> {
                FrontendOwnerLease(0, 60_000, MutableStateFlow(
                    leaseCapabilities.copy(frontendErrorObservation = status),
                )) { 0 }
            }
        }
        for (lease in listOf(0L, 59_999L, 60_001L, Long.MAX_VALUE)) {
            assertFailsWith<IllegalStateException> {
                FrontendOwnerLease(0, lease, MutableStateFlow(leaseCapabilities)) { 0 }
            }
        }
        assertFailsWith<IllegalStateException> {
            FrontendOwnerLease(0, 60_000, MutableStateFlow(leaseCapabilities)) { 60_000 }
        }
    }
    test("reply correlation rejects a different operation reservation binding or attempt without payload diagnostics") {
        val reservation = LocalOAuthReservation("r".repeat(64))
        val other = LocalOAuthReservation("s".repeat(64))
        val binding = LocalOAuthBinding(1, "private-state-marker", "https://provider/private-url-marker")
        val mismatches = listOf(
            LocalFrontendHostRequest.RenewOwner to LocalFrontendHostReply.OwnerClosed,
            LocalFrontendHostRequest.ObserveError(null) to renewed(),
            LocalFrontendHostRequest.CloseCallback(reservation) to LocalFrontendHostReply.CallbackClosed(other),
            LocalFrontendHostRequest.AwaitCallback(reservation) to LocalFrontendHostReply.CallbackReceived(other, 1, "private-callback-marker"),
            LocalFrontendHostRequest.BindCallback(reservation, binding) to LocalFrontendHostReply.CallbackBound(
                reservation, LocalOAuthBinding(2, binding.state, binding.authorizationUrl),
            ),
            LocalFrontendHostRequest.BindCallback(reservation, binding) to LocalFrontendHostReply.CallbackBound(
                reservation, LocalOAuthBinding(1, "other", binding.authorizationUrl),
            ),
            LocalFrontendHostRequest.BindCallback(reservation, binding) to LocalFrontendHostReply.CallbackBound(
                reservation, LocalOAuthBinding(1, binding.state, "https://provider/other"),
            ),
            LocalFrontendHostRequest.OpenRegisteredAuthorization(reservation, 1) to
                LocalFrontendHostReply.AuthorizationOpened(reservation, 2),
        )
        for ((request, reply) in mismatches) {
            val failure = assertFailsWith<FrontendHostInvalidResponse> { validateHostReply(request, reply) }
            assertFalse(failure.toString().contains("private-"))
        }
        val reply = LocalFrontendHostReply.CallbackBound(reservation, binding)
        assertSame(reply, validateHostReply(LocalFrontendHostRequest.BindCallback(reservation, binding), reply))
    }
}
