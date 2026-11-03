@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.backend.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

private const val registryOrigin = "http://127.0.0.1:1234"
private val registryCapabilities = FrontendHostCapabilities(
    FrontendCapabilityStatus.Ready, FrontendCapabilityStatus.Ready,
    FrontendCapabilityStatus.Ready, FrontendCapabilityStatus.Ready,
)

internal val frontendCompanionOwnersTest by testSuite {
    test("HTTP caller cancellation stops only its dispatched operation and keeps the owner renewable") {
        runTest {
            val fixture = OwnersFixture(this)
            try {
                val host = fixture.owners.open(registryOrigin, "/launch").host
                fixture.resources.single().wait = true
                val waiting = async {
                    fixture.owners.call(registryOrigin, fixture.call(
                        host, LocalFrontendHostRequest.AwaitCallback(LocalOAuthReservation("reserved")),
                    ))
                }
                runCurrent()
                assertFalse(waiting.isCompleted)
                waiting.cancel()
                runCurrent()
                assertTrue(waiting.isCompleted, "Replacing the HTTP Job must not detach a pending request.")
                assertEquals(1, fixture.resources.single().cancelledCalls)
                assertTrue(fixture.resourceJobs.single().isActive)
                assertIs<LocalFrontendHostReply.OwnerRenewed>(
                    fixture.owners.call(registryOrigin, fixture.call(host, LocalFrontendHostRequest.RenewOwner))?.reply,
                )
                assertEquals(0, fixture.resources.single().closes)
            } finally {
                fixture.owners.closeAndJoin()
            }
        }
    }
    test("slow acquisition and registration do not block an existing owner's renewal") {
        runTest {
            for (slowRegister in listOf(false, true)) {
                val fixture = OwnersFixture(this)
                val gate = CompletableDeferred<Unit>()
                try {
                    val host = fixture.owners.open(registryOrigin, "/launch").host
                    if (slowRegister) fixture.beforeRegister = { gate.await() }
                    else fixture.beforeAcquire = { _, _ -> gate.await() }
                    val opening = async { fixture.owners.open(registryOrigin, "/launch") }
                    runCurrent()
                    assertFalse(opening.isCompleted)
                    val renewal = async {
                        fixture.owners.call(registryOrigin, fixture.call(host, LocalFrontendHostRequest.RenewOwner))
                    }
                    runCurrent()
                    assertTrue(renewal.isCompleted, "Another page's slow open must not hold the registry lock")
                    assertIs<LocalFrontendHostReply.OwnerRenewed>(renewal.await()?.reply)
                    gate.complete(Unit)
                    opening.await()
                } finally {
                    gate.complete(Unit)
                    fixture.owners.closeAndJoin()
                }
            }
        }
    }
    test("shutdown cancels pending acquisition and waits for a late resource without publishing it") {
        runTest {
            val fixture = OwnersFixture(this)
            val gate = CompletableDeferred<Unit>()
            fixture.beforeAcquire = { _, _ -> withContext(NonCancellable) { gate.await() } }
            val opening = async { runCatching { fixture.owners.open(registryOrigin, "/launch") } }
            try {
                runCurrent()
                val shutdown = async { fixture.owners.closeAndJoin() }
                runCurrent()
                assertFalse(shutdown.isCompleted)
                gate.complete(Unit)
                shutdown.await()
                assertTrue(opening.await().isFailure, "A resource acquired after shutdown must never be published")
                assertEquals(1, fixture.resources.single().closes)
                assertEquals(fixture.registered, fixture.revoked)
                assertTrue(fixture.resourceJobs.all { it.isCompleted })
            } finally {
                gate.complete(Unit)
                fixture.owners.closeAndJoin()
            }
        }
    }
    test("HTTP cancellation after publication but before await resumes closes the unpublished handoff") {
        runTest {
            val fixture = OwnersFixture(this)
            val acquireGate = CompletableDeferred<Unit>()
            val acquired = CompletableDeferred<Unit>()
            fixture.beforeAcquire = { _, _ -> acquireGate.await() }
            fixture.afterAcquire = { acquired.complete(Unit) }
            val opening = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.owners.open(registryOrigin, "/launch")
            }
            val cancelHTTP = launch {
                acquired.await()
                opening.cancel()
            }
            try {
                runCurrent()
                acquireGate.complete(Unit)
                runCurrent()
                cancelHTTP.join()
                opening.join()
                assertTrue(opening.isCancelled)
                assertEquals(1, fixture.resources.single().closes)
                assertEquals(fixture.registered, fixture.revoked)
                assertTrue(fixture.resourceJobs.all { it.isCompleted })
            } finally {
                acquireGate.complete(Unit)
                cancelHTTP.cancelAndJoin()
                opening.cancelAndJoin()
                fixture.owners.closeAndJoin()
            }
        }
    }
    test("HTTP cancellation before the acquisition task is dispatched releases its reserved slot") {
        runTest {
            val fixture = OwnersFixture(this)
            val opening = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.owners.open(registryOrigin, "/launch")
            }
            opening.cancel()
            opening.join()
            try {
                assertTrue(fixture.resources.all { it.closes == 1 })
                assertTrue(fixture.resourceJobs.all { it.isCompleted })
                repeat(LocalFrontendHostMaxOwners) { fixture.owners.open(registryOrigin, "/launch") }
            } finally { fixture.owners.closeAndJoin() }
        }
    }
    test("host cancellation before the worker starts still executes its cleanup finally") {
        runTest {
            val hostJob = SupervisorJob(coroutineContext[Job])
            val fixture = OwnersFixture(CoroutineScope(coroutineContext + hostJob))
            val opening = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { fixture.owners.open(registryOrigin, "/launch") }
            }
            try {
                hostJob.cancel()
                runCurrent()
                assertTrue(opening.await().isFailure)
                hostJob.join()
                assertTrue(fixture.registered.isEmpty())
                assertTrue(fixture.resources.isEmpty())
                assertEquals(1, fixture.revoked.size)
                fixture.owners.closeAndJoin()
            } finally {
                hostJob.cancelAndJoin()
                fixture.owners.closeAndJoin()
            }
        }
    }
    test("close cancels lazy resource children without starting them or losing the resource") {
        runTest {
            val fixture = OwnersFixture(this)
            var child: Job? = null
            fixture.beforeAcquire = { ownerScope, _ ->
                child = ownerScope.launch(start = CoroutineStart.LAZY) { error("Must never start") }
            }
            try {
                val host = fixture.owners.open(registryOrigin, "/launch").host
                val closing = async {
                    fixture.owners.call(registryOrigin, fixture.call(host, LocalFrontendHostRequest.CloseOwner))
                }
                runCurrent()
                assertTrue(closing.isCompleted)
                assertIs<LocalFrontendHostReply.OwnerClosed>(closing.await()?.reply)
                assertTrue(requireNotNull(child).isCancelled)
                assertTrue(fixture.resourceJobs.single().isCompleted)
                assertEquals(1, fixture.resources.single().closes)
            } finally { fixture.owners.closeAndJoin() }
        }
    }
    test("shutdown during slow registration revokes that exact grant without starting acquisition") {
        runTest {
            val fixture = OwnersFixture(this)
            val gate = CompletableDeferred<Unit>()
            fixture.beforeRegister = { withContext(NonCancellable) { gate.await() } }
            val opening = async { runCatching { fixture.owners.open(registryOrigin, "/requested") } }
            try {
                runCurrent()
                val shutdown = async { fixture.owners.closeAndJoin() }
                runCurrent()
                assertFalse(shutdown.isCompleted)
                assertEquals("/requested", fixture.registered.single().workingDirectory)
                gate.complete(Unit)
                shutdown.await()
                assertTrue(opening.await().isFailure)
                assertTrue(fixture.resources.isEmpty())
                assertEquals(fixture.registered, fixture.revoked)
            } finally {
                gate.complete(Unit)
                fixture.owners.closeAndJoin()
            }
        }
    }
    test("lease starts on publication and completed closes do not consume future owner slots") {
        runTest {
            val fixture = OwnersFixture(this)
            val gate = CompletableDeferred<Unit>()
            fixture.beforeAcquire = { _, _ -> gate.await() }
            try {
                val opening = async { fixture.owners.open(registryOrigin, "/launch") }
                runCurrent()
                fixture.now = LocalFrontendHostOwnerLeaseMillis * 2
                gate.complete(Unit)
                val first = opening.await().host
                assertEquals(LocalFrontendHostOwnerLeaseMillis, first.leaseMillis)
                fixture.now += LocalFrontendHostOwnerLeaseMillis - 1
                assertNotNull(fixture.owners.call(registryOrigin,
                    fixture.call(first, LocalFrontendHostRequest.ObserveError(null))))
                fixture.now++
                fixture.owners.expire()
                assertEquals(1, fixture.resources.single().closes)
                val identities = mutableSetOf(first.owner.value)
                repeat(LocalFrontendHostMaxOwners * 2) {
                    val host = fixture.owners.open(registryOrigin, "/launch").host
                    assertTrue(identities.add(host.owner.value))
                    fixture.owners.call(registryOrigin, fixture.call(host, LocalFrontendHostRequest.CloseOwner))
                }
                assertEquals(fixture.registered, fixture.revoked)
            } finally {
                gate.complete(Unit)
                fixture.owners.closeAndJoin()
            }
        }
    }
    test("concurrent CloseOwner calls share the cleanup gate and exact authentication") {
        runTest {
            val fixture = OwnersFixture(this)
            val gate = CompletableDeferred<Unit>()
            try {
                val host = fixture.owners.open(registryOrigin, "/launch").host
                fixture.resources.single().onClose = { gate.await() }
                val first = async {
                    fixture.owners.call(registryOrigin, fixture.call(host, LocalFrontendHostRequest.CloseOwner))
                }
                runCurrent()
                val second = async {
                    fixture.owners.call(registryOrigin, fixture.call(host, LocalFrontendHostRequest.CloseOwner))
                }
                val renewal = async {
                    fixture.owners.call(registryOrigin, fixture.call(host, LocalFrontendHostRequest.RenewOwner))
                }
                runCurrent()
                assertFalse(first.isCompleted)
                assertFalse(second.isCompleted, "Close ACK cannot precede resource cleanup")
                assertEquals(LocalFrontendHostFailure.OwnerClosed,
                    assertIs<LocalFrontendHostReply.Failure>(renewal.await()?.reply).reason)
                assertNull(fixture.owners.call("http://localhost:1234",
                    fixture.call(host, LocalFrontendHostRequest.CloseOwner)))
                assertNull(fixture.owners.call(registryOrigin,
                    LocalFrontendHostCall(1, host.hostInstanceId, "wrong", host.owner,
                        LocalFrontendHostRequest.CloseOwner)))
                gate.complete(Unit)
                assertIs<LocalFrontendHostReply.OwnerClosed>(first.await()?.reply)
                assertIs<LocalFrontendHostReply.OwnerClosed>(second.await()?.reply)
                assertEquals(1, fixture.resources.single().closes)
                assertEquals(1, fixture.revoked.size)
            } finally {
                gate.complete(Unit)
                fixture.owners.closeAndJoin()
            }
        }
    }
    test("concurrent close and repeated shutdown preserve the same failure and suppressed cleanup") {
        runTest {
            val fixture = OwnersFixture(this)
            val gate = CompletableDeferred<Unit>()
            val cleanup = IllegalStateException("fixture cleanup")
            val revocation = IllegalStateException("fixture revoke")
            val host = fixture.owners.open(registryOrigin, "/launch").host
            fixture.resources.single().onClose = { gate.await(); throw cleanup }
            fixture.beforeRevoke = { throw revocation }
            try {
                val first = async { runCatching {
                    fixture.owners.call(registryOrigin, fixture.call(host, LocalFrontendHostRequest.CloseOwner))
                } }
                runCurrent()
                val second = async { runCatching {
                    fixture.owners.call(registryOrigin, fixture.call(host, LocalFrontendHostRequest.CloseOwner))
                } }
                val shutdown = async { runCatching { fixture.owners.closeAndJoin() } }
                runCurrent()
                assertFalse(second.isCompleted)
                assertFalse(shutdown.isCompleted, "Shutdown must include entries already closing")
                gate.complete(Unit)
                assertSame(cleanup, first.await().exceptionOrNull())
                assertSame(cleanup, second.await().exceptionOrNull())
                assertSame(cleanup, shutdown.await().exceptionOrNull())
                assertSame(cleanup, runCatching { fixture.owners.closeAndJoin() }.exceptionOrNull())
                assertEquals(listOf(revocation), cleanup.suppressedExceptions)
                assertEquals(1, fixture.resources.single().closes)
            } finally {
                gate.complete(Unit)
                runCatching { fixture.owners.closeAndJoin() }
            }
        }
    }
    test("shutdown cancels every owner before waiting and retains all cleanup failures") {
        runTest {
            val fixture = OwnersFixture(this)
            val gate = CompletableDeferred<Unit>()
            val failures = List(2) { IllegalStateException("fixture cleanup $it") }
            repeat(2) { fixture.owners.open(registryOrigin, "/launch") }
            fixture.resources.forEachIndexed { index, resource ->
                resource.onClose = {
                    assertTrue(fixture.resourceJobs.all { !it.isActive })
                    gate.await()
                    throw failures[index]
                }
            }
            try {
                val shutdown = async { runCatching { fixture.owners.closeAndJoin() } }
                runCurrent()
                assertFalse(shutdown.isCompleted)
                assertTrue(fixture.resources.all { it.closes == 1 })
                gate.complete(Unit)
                assertSame(failures[0], shutdown.await().exceptionOrNull())
                assertEquals(listOf(failures[1]), failures[0].suppressedExceptions)
                assertSame(failures[0], runCatching { fixture.owners.closeAndJoin() }.exceptionOrNull())
            } finally {
                gate.complete(Unit)
                runCatching { fixture.owners.closeAndJoin() }
            }
        }
    }
    test("pending and closing owners both count toward the original owner budget") {
        runTest {
            val fixture = OwnersFixture(this)
            val acquireGate = CompletableDeferred<Unit>()
            val closeGate = CompletableDeferred<Unit>()
            try {
                val host = fixture.owners.open(registryOrigin, "/launch").host
                repeat(LocalFrontendHostMaxOwners - 2) { fixture.owners.open(registryOrigin, "/launch") }
                fixture.beforeAcquire = { _, _ -> acquireGate.await() }
                val pending = async { fixture.owners.open(registryOrigin, "/launch") }
                runCurrent()
                fixture.resources.first().onClose = { closeGate.await() }
                val closing = async {
                    fixture.owners.call(registryOrigin, fixture.call(host, LocalFrontendHostRequest.CloseOwner))
                }
                runCurrent()
                val excess = async { runCatching { fixture.owners.open(registryOrigin, "/launch") } }
                runCurrent()
                assertTrue(excess.isCompleted, "Budget rejection must not wait for a pending acquisition")
                assertIs<IllegalStateException>(excess.await().exceptionOrNull())
                assertEquals(LocalFrontendHostMaxOwners, fixture.registered.size)
                closeGate.complete(Unit)
                closing.await()
                acquireGate.complete(Unit)
                pending.await()
                fixture.owners.open(registryOrigin, "/launch")
            } finally {
                acquireGate.complete(Unit)
                closeGate.complete(Unit)
                fixture.owners.closeAndJoin()
            }
        }
    }
    test("failed bootstrap preserves its primary exception when resource cleanup also fails") {
        runTest {
            val fixture = OwnersFixture(this)
            val cleanup = IllegalStateException("fixture cleanup")
            fixture.canonicalDirectory = "/" + "界".repeat(LocalFrontendHostMaxFrameBytes)
            fixture.afterAcquire = { it.onClose = { throw cleanup } }
            try {
                val primary = assertFailsWith<IllegalStateException> {
                    fixture.owners.open(registryOrigin, "/launch")
                }
                assertEquals("Frontend server bootstrap limit exceeded.", primary.message)
                assertEquals(listOf(cleanup), primary.suppressedExceptions)
                assertEquals(fixture.registered, fixture.revoked)
            } finally { fixture.owners.closeAndJoin() }
        }
    }
    test("each page acquires independent scoped grants credentials and opaque owners") {
        runTest {
            val fixture = OwnersFixture(this)
            try {
                val first = fixture.owners.open(registryOrigin, "/launch")
                val second = fixture.owners.open(registryOrigin, "/launch")
                assertNotEquals(first.host.owner.value, second.host.owner.value)
                assertEquals(4, setOf(
                    first.host.credential, second.host.credential,
                    first.endpoint.password, second.endpoint.password,
                ).size)
                assertEquals("/canonical", first.workingDirectory)
                assertEquals("/native-launch", first.host.launchDirectory)
                assertEquals(2, fixture.registered.size)
            } finally { fixture.owners.closeAndJoin() }
            assertEquals(2, fixture.revoked.size)
            assertTrue(fixture.resources.all { it.closes == 1 })
        }
    }
    test("wrong Origin credential owner protocol and instance cannot dispatch") {
        runTest {
            val fixture = OwnersFixture(this)
            try {
                val host = fixture.owners.open(registryOrigin, "/launch").host
                val request = LocalFrontendHostRequest.ObserveError("private-fixture-error")
                val valid = fixture.call(host, request)
                for (call in listOf(
                    LocalFrontendHostCall(0, host.hostInstanceId, host.credential, host.owner, request),
                    LocalFrontendHostCall(1, "other", host.credential, host.owner, request),
                    LocalFrontendHostCall(1, host.hostInstanceId, "wrong", host.owner, request),
                    LocalFrontendHostCall(1, host.hostInstanceId, host.credential, LocalFrontendOwner("wrong"), request),
                )) {
                    assertNull(fixture.owners.call(registryOrigin, call))
                }
                assertNull(fixture.owners.call("http://localhost:1234", valid))
                assertEquals(0, fixture.resources.single().calls)
            } finally { fixture.owners.closeAndJoin() }
        }
    }
    test("only renewal extends a live owner and backend offline closes without replacement") {
        runTest {
            val fixture = OwnersFixture(this)
            try {
                val host = fixture.owners.open(registryOrigin, "/launch").host
                fixture.now = 50_000
                val renewed = fixture.owners.call(registryOrigin, fixture.call(host, LocalFrontendHostRequest.RenewOwner))
                assertIs<LocalFrontendHostReply.OwnerRenewed>(renewed?.reply)
                fixture.now = 70_000
                assertNotNull(fixture.owners.call(
                    registryOrigin, fixture.call(host, LocalFrontendHostRequest.ObserveError(null)),
                ))
                fixture.online = false
                val offline = fixture.owners.call(registryOrigin, fixture.call(host, LocalFrontendHostRequest.RenewOwner))
                assertEquals(LocalFrontendHostFailure.BackendOffline,
                    assertIs<LocalFrontendHostReply.Failure>(offline?.reply).reason)
                assertEquals(1, fixture.revoked.size)
                assertNull(fixture.owners.call(registryOrigin, fixture.call(host, LocalFrontendHostRequest.RenewOwner)))
            } finally { fixture.owners.closeAndJoin() }
        }
    }
    test("expiry rejects stale capability and same requested directory does not reuse its identity") {
        runTest {
            val fixture = OwnersFixture(this)
            try {
                val first = fixture.owners.open(registryOrigin, "/launch").host
                fixture.now = 60_000
                assertNull(fixture.owners.call(
                    registryOrigin, fixture.call(first, LocalFrontendHostRequest.RenewOwner),
                ))
                assertEquals(1, fixture.resources.single().closes)
                val second = fixture.owners.open(registryOrigin, "/launch").host
                assertNotEquals(first.owner.value, second.owner.value)
            } finally { fixture.owners.closeAndJoin() }
        }
    }
    test("owner close cancels local waiting work and does not close another owner") {
        runTest {
            val fixture = OwnersFixture(this)
            try {
                val first = fixture.owners.open(registryOrigin, "/launch").host
                val second = fixture.owners.open(registryOrigin, "/launch").host
                fixture.resources[0].wait = true
                val waiting = async {
                    fixture.owners.call(registryOrigin, fixture.call(
                        first, LocalFrontendHostRequest.AwaitCallback(LocalOAuthReservation("reserved")),
                    ))
                }
                runCurrent()
                assertFalse(waiting.isCompleted)
                val closed = fixture.owners.call(registryOrigin, fixture.call(first, LocalFrontendHostRequest.CloseOwner))
                assertIs<LocalFrontendHostReply.OwnerClosed>(closed?.reply)
                assertEquals(LocalFrontendHostFailure.OwnerClosed,
                    assertIs<LocalFrontendHostReply.Failure>(waiting.await()?.reply).reason)
                assertEquals(1, fixture.resources[0].closes)
                assertEquals(0, fixture.resources[1].closes)
                assertIs<LocalFrontendHostReply.OwnerRenewed>(
                    fixture.owners.call(registryOrigin, fixture.call(second, LocalFrontendHostRequest.RenewOwner))?.reply,
                )
            } finally { fixture.owners.closeAndJoin() }
        }
    }
    test("lost registration and resource acquisition failures revoke only their own grant") {
        runTest {
            for (failRegister in listOf(false, true)) {
                val fixture = OwnersFixture(this, failRegister, !failRegister)
                try {
                    assertFailsWith<IllegalStateException> { fixture.owners.open(registryOrigin, "/launch") }
                    assertEquals(1, fixture.registered.size)
                    assertEquals(fixture.registered, fixture.revoked)
                } finally { fixture.owners.closeAndJoin() }
            }
        }
    }
    test("owner budget rejects before acquiring more resources and closed registry cannot reopen") {
        runTest {
            val fixture = OwnersFixture(this)
            try {
                repeat(LocalFrontendHostMaxOwners) { fixture.owners.open(registryOrigin, "/launch") }
                assertFailsWith<IllegalStateException> { fixture.owners.open(registryOrigin, "/launch") }
                assertEquals(LocalFrontendHostMaxOwners, fixture.registered.size)
            } finally { fixture.owners.closeAndJoin() }
            assertFailsWith<IllegalStateException> { fixture.owners.open(registryOrigin, "/launch") }
            assertEquals(LocalFrontendHostMaxOwners, fixture.revoked.size)
        }
    }
    test("oversize bootstrap is rejected before publication and releases its acquired grant and resources") {
        runTest {
            val fixture = OwnersFixture(this, canonical = "/" + "界".repeat(LocalFrontendHostMaxFrameBytes))
            try {
                assertFailsWith<IllegalStateException> { fixture.owners.open(registryOrigin, "/launch") }
                assertEquals(1, fixture.registered.size)
                assertEquals(1, fixture.revoked.size)
                assertEquals(1, fixture.resources.single().closes)
                fixture.canonicalDirectory = "/canonical"
                assertEquals("/canonical", fixture.owners.open(registryOrigin, "/launch").workingDirectory)
            } finally { fixture.owners.closeAndJoin() }
            assertEquals(2, fixture.revoked.size)
        }
    }
}

private class OwnersFixture(
    scope: CoroutineScope,
    failRegister: Boolean = false,
    failAcquire: Boolean = false,
    canonical: String = "/canonical",
) {
    var now = 0L
    var online = true
    var canonicalDirectory = canonical
    var beforeRegister: suspend (LocalBrowserGrant) -> Unit = {}
    var beforeRevoke: suspend (LocalBrowserGrant) -> Unit = {}
    var beforeAcquire: suspend (CoroutineScope, LocalFrontendOwner) -> Unit = { _, _ -> }
    var afterAcquire: suspend (FakeCompanionResources) -> Unit = {}
    private var serial = 0
    val registered = mutableListOf<LocalBrowserGrant>()
    val revoked = mutableListOf<LocalBrowserGrant>()
    val resources = mutableListOf<FakeCompanionResources>()
    val resourceJobs = mutableListOf<Job>()
    val owners = FrontendCompanionOwners(
        scope, BackendEndpoint("backend", 1, 1235, "m".repeat(64), "fixture"),
        "h".repeat(64), registryOrigin, "/native-launch", "device",
        { (++serial).toString(16).padStart(32, '0') + "e".repeat(32) }, { now }, { online },
        register = {
            registered += it
            beforeRegister(it)
            if (failRegister) error("lost private registration reply")
            canonicalDirectory
        },
        revoke = { revoked += it; beforeRevoke(it) },
        acquire = { ownerScope, owner ->
            resourceJobs += requireNotNull(ownerScope.coroutineContext[Job])
            beforeAcquire(ownerScope, owner)
            if (failAcquire) error("private acquisition failure")
            FakeCompanionResources().also { resources += it; afterAcquire(it) }
        },
    )
    fun call(host: LocalFrontendHostDescriptor, request: LocalFrontendHostRequest) =
        LocalFrontendHostCall(1, host.hostInstanceId, host.credential, host.owner, request)
}

private class FakeCompanionResources : FrontendCompanionResources {
    override val capabilities = registryCapabilities
    var calls = 0
    var closes = 0
    var wait = false
    var cancelledCalls = 0
    var onClose: suspend () -> Unit = {}
    override suspend fun call(request: LocalFrontendHostRequest): LocalFrontendHostReply {
        calls++
        if (wait) {
            try { awaitCancellation() }
            finally { cancelledCalls++ }
        }
        return LocalFrontendHostReply.ErrorObserved(LocalErrorObservationAdmission.Admitted)
    }
    override suspend fun closeAndJoin() { closes++; onClose() }
}
