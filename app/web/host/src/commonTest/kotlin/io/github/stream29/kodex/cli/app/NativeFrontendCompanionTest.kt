package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.backend.FrontendCapabilityStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal val nativeFrontendCompanionTest by testSuite {
    test("the scoped owner advertises active capabilities and transitions cleanly on close") {
        runTest {
            val job = SupervisorJob(coroutineContext[Job])
            val resources = acquireNativeFrontendCompanion(CoroutineScope(coroutineContext + job))
            try {
                assertEquals(FrontendCapabilityStatus.Ready, resources.capabilities.directoryBrowsing)
                assertEquals(FrontendCapabilityStatus.Ready, resources.capabilities.contextPathEvaluation)
                resources.closeAndJoin()
                assertEquals(FrontendCapabilityStatus.OwnerClosed, resources.capabilities.directoryBrowsing)
                assertEquals(FrontendCapabilityStatus.OwnerClosed, resources.capabilities.contextPathEvaluation)
                resources.closeAndJoin()
            } finally { job.cancelAndJoin() }
        }
    }

    test("a cancelled owner cannot acquire resources") {
        runTest {
            val job = SupervisorJob(coroutineContext[Job])
            job.cancelAndJoin()
            assertFailsWith<CancellationException> {
                acquireNativeFrontendCompanion(CoroutineScope(coroutineContext + job))
            }
        }
    }
}
