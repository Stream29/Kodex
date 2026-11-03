package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.backend.FrontendCapabilityStatus
import io.github.stream29.kodex.backend.LocalFrontendHostFailure
import io.github.stream29.kodex.backend.LocalFrontendHostReply
import io.github.stream29.kodex.backend.LocalFrontendHostRequest
import io.github.stream29.kodex.backend.LocalOAuthProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

internal val nativeFrontendCompanionTest by testSuite {
    test("the scoped owner advertises no retired frontend effects and rejects their requests") {
        runTest {
            val job = SupervisorJob(coroutineContext[Job])
            val resources = acquireNativeFrontendCompanion(CoroutineScope(coroutineContext + job))
            try {
                assertEquals(FrontendCapabilityStatus.Ready, resources.capabilities.directoryBrowsing)
                assertEquals(FrontendCapabilityStatus.Ready, resources.capabilities.contextPathEvaluation)
                assertEquals(FrontendCapabilityStatus.NoCompanion, resources.capabilities.oauthCallbacks)
                assertEquals(FrontendCapabilityStatus.NoCompanion, resources.capabilities.frontendErrorObservation)
                for (request in listOf(
                    LocalFrontendHostRequest.ReserveCallback(LocalOAuthProvider.OpenAi, null),
                    LocalFrontendHostRequest.ObserveError("fixture"),
                )) {
                    assertEquals(
                        LocalFrontendHostFailure.CapabilityUnavailable,
                        assertIs<LocalFrontendHostReply.Failure>(resources.call(request)).reason,
                    )
                }
                resources.closeAndJoin()
                assertEquals(FrontendCapabilityStatus.OwnerClosed, resources.capabilities.directoryBrowsing)
                assertEquals(
                    LocalFrontendHostFailure.OwnerClosed,
                    assertIs<LocalFrontendHostReply.Failure>(
                        resources.call(LocalFrontendHostRequest.ObserveError(null)),
                    ).reason,
                )
                resources.closeAndJoin()
            } finally { job.cancelAndJoin() }
        }
    }

    test("a cancelled owner cannot acquire resources") {
        runTest {
            val job = SupervisorJob(coroutineContext[Job])
            job.cancelAndJoin()
            assertFailsWith<kotlinx.coroutines.CancellationException> {
                acquireNativeFrontendCompanion(CoroutineScope(coroutineContext + job))
            }
        }
    }
}
