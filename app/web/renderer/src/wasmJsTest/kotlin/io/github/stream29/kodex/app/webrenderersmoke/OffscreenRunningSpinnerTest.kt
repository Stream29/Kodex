@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.app.sessiontabbar.contract.SessionTabIdentity
import io.github.stream29.kodex.app.sessiontabbar.contract.SessionTabPresentation
import io.github.stream29.kodex.app.ui.compose.rememberComposeSessionCatalogRenderer
import io.github.stream29.kodex.app.ui.compose.rememberComposeSessionTabBarRenderer
import io.github.stream29.kodex.cli.sessioncatalog.SessionCatalogRows
import kotlinx.coroutines.delay
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val offscreenRunningSpinnerTest by testSuite {
    test("catalog draws an animated running spinner and removes it for idle resident or archived rows") { offscreenTest {
        var entry by mutableStateOf(SessionCatalogEntry(7, "catalog spinner", isActive = true))
        val scene = OffscreenScene.create(width = 320, height = 240) {
            rememberComposeSessionCatalogRenderer().Dialog(
                false, SessionCatalogRows.Entries(listOf(entry)), {}, {}, {}, { _, _ -> },
            )
        }
        try {
            scene.settle { scene.hasText("catalog spinner") }
            assertTrue(scene.runningSpinners().isEmpty())
            entry = entry.copy(running = true)
            scene.settle("catalog must draw a spinner, not only Running text") {
                scene.runningSpinners().size == 1
            }
            assertTrue(scene.hasText("Running"))
            scene.assertRunningSpinnerAnimates()
            entry = entry.copy(running = false, archived = true, isActive = false)
            scene.settle { scene.runningSpinners().isEmpty() && !scene.hasText("Running") }
            scene.assertIdleFrameIsStable()
        } finally { scene.close() }
    } }

    test("tab draws the same animated running spinner instead of a static dot and removes it on stop") { offscreenTest {
        var running by mutableStateOf(false)
        val scene = OffscreenScene.create(width = 320, height = 240) {
            rememberComposeSessionTabBarRenderer().Tab(
                SessionTabPresentation(SessionTabIdentity("spinner-tab"), "tab spinner", running),
                true, true, {}, {},
            )
        }
        try {
            scene.settle { scene.hasText("tab spinner") }
            assertTrue(scene.runningSpinners().isEmpty())
            running = true
            scene.settle("tab must draw a spinner, not only a static dot") {
                scene.runningSpinners().size == 1
            }
            scene.assertRunningSpinnerAnimates()
            running = false
            scene.settle { scene.runningSpinners().isEmpty() && !scene.hasDescription("Running") }
            scene.assertIdleFrameIsStable()
        } finally { scene.close() }
    } }
}

private fun OffscreenScene.runningSpinners() = nodes().filter {
    it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) == ProgressBarRangeInfo.Indeterminate
}

private suspend fun OffscreenScene.assertRunningSpinnerAnimates() {
    val spinner = runningSpinners().single()
    assertEquals(listOf("Running"), spinner.config.getOrNull(SemanticsProperties.ContentDescription))
    val merged = buildList {
        fun visit(node: SemanticsNode) { add(node); node.children.forEach(::visit) }
        scene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }
    }.filter { it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) == ProgressBarRangeInfo.Indeterminate }
    assertEquals(1, merged.size, "The spinner must retain its own merged accessibility node.")
    assertFalse(merged.single().config.getOrNull(SemanticsProperties.Role) in listOf(Role.Button, Role.Tab),
        "Progress semantics must not merge into the containing button or tab.")
    val bounds = spinner.boundsInWindow
    assertTrue(bounds.width > 0 && bounds.height > 0)
    assertTrue(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= 320 && bounds.bottom <= 240)
    val first = frame()
    delay(150)
    advance()
    val next = frame()
    assertFalse(first.contentEquals(next), "The actual spinner raster must change, not only its semantics.")
    assertEquals(bounds, runningSpinners().single().boundsInWindow, "Animation must not shift the row.")
}

private suspend fun OffscreenScene.assertIdleFrameIsStable() {
    val first = frame()
    delay(150)
    advance()
    assertContentEquals(first, frame(), "Idle rows must not retain a running animation.")
}
