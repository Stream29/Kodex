package io.github.stream29.kodex.tool.applypatch

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatchToolExecutionResult
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPatchToolEvent
import io.github.stream29.kodex.utils.applypatch.Patch
import kotlinx.coroutines.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

val applyPatchFailureBoundaryTest by testSuite {
    test("unexpected working-directory failure is a matching patch failure") {
        val tool = ApplyPatchTool(ApplyPatchToolClient(workingDirectoryProvider = {
            error("private directory error")
        }))
        val pending = PendingPatchToolEvent(
            callId = "patch-call",
            diff = Patch("*** Begin Patch\n*** End Patch", emptyList()),
        )
        val event = assertIs<StablePatchToolEvent>(tool.handle(pending))
        assertEquals(pending.callId, event.callId)
        assertEquals(pending.diff, event.diff)
        assertEquals(
            StablePatchToolExecutionResult.Failure("Tool execution failed."),
            event.result,
        )
    }

    test("cancellation from the patch client propagates") {
        val cancellation = CancellationException("cancel")
        val tool = ApplyPatchTool(ApplyPatchToolClient(workingDirectoryProvider = { throw cancellation }))
        val pending = PendingPatchToolEvent(
            callId = "patch-cancel",
            diff = Patch("*** Begin Patch\n*** End Patch", emptyList()),
        )
        assertEquals(cancellation, assertFailsWith<CancellationException> { tool.handle(pending) })
    }
}
