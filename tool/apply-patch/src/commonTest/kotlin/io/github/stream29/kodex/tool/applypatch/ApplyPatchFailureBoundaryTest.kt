package io.github.stream29.kodex.tool.applypatch

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPatch
import io.github.stream29.kodex.utils.applypatch.Patch
import kotlinx.coroutines.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

val applyPatchFailureBoundaryTest by testSuite {
    test("unexpected working-directory failure propagates to runtime caller") {
        val tool = ApplyPatchTool(ApplyPatchToolClient(workingDirectoryProvider = {
            error("private directory error")
        }))
        val pending = PendingPatch(
            callId = "patch-call",
            diff = Patch("*** Begin Patch\n*** End Patch", emptyList()),
        )
        val failure = assertFailsWith<IllegalStateException> { tool.handle(pending) }
        assertEquals("private directory error", failure.message)
    }

    test("cancellation from the patch client propagates") {
        val cancellation = CancellationException("cancel")
        val tool = ApplyPatchTool(ApplyPatchToolClient(workingDirectoryProvider = { throw cancellation }))
        val pending = PendingPatch(
            callId = "patch-cancel",
            diff = Patch("*** Begin Patch\n*** End Patch", emptyList()),
        )
        assertEquals(cancellation, assertFailsWith<CancellationException> { tool.handle(pending) })
    }
}
