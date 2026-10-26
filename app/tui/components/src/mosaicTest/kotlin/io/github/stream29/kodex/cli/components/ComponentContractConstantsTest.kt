package io.github.stream29.kodex.cli.components

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals

private const val FrameDurationMillis: Int = RunningIndicatorFrameDurationMillis
private const val SettingsButtonLabel: String = SettingsLabel

val componentContractConstantsTest by testSuite {
    test("frame duration and settings label remain compile-time constants") {
        assertEquals(100, FrameDurationMillis)
        assertEquals("Settings", SettingsButtonLabel)
    }
}
