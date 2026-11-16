package io.github.stream29.kodex.agentstate.tool

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.tool.applypatch.ApplyPatchTools
import io.github.stream29.kodex.tool.awaitevent.AwaitEventTools
import io.github.stream29.kodex.tool.plan.PlanTools
import io.github.stream29.kodex.tool.readmedia.ReadMediaTools
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecTools
import kotlin.test.assertEquals

val kodexVisibleToolSpecsTest by testSuite {
    test("exposes exact 5 static model tools in deterministic order") {
        assertEquals(
            listOf(
                UnifiedExecTools.execCommandSpec,
                ApplyPatchTools.spec,
                ReadMediaTools.spec,
                PlanTools.spec,
                AwaitEventTools.spec,
            ),
            visibleToolSpecs(),
        )
    }
}
