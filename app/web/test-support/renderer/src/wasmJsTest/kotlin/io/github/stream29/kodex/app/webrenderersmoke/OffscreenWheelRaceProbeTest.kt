package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.sessiontabbar.contract.*
import io.github.stream29.kodex.app.ui.compose.rememberComposeSessionTabBarRenderer
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

val offscreenWheelRaceProbeTest by testSuite {
    test("vertical wheel moves overflowing tabs and reverses without selecting") { offscreenTest {
        val tabs = List(40) { SessionTabPresentation(SessionTabIdentity("wheel-$it"), "Wheel $it") }
        var commands = 0
        val callbacks = object : SessionTabBarCallbacks {
            override fun select(identity: SessionTabIdentity) { commands++ }
            override fun close(identity: SessionTabIdentity) { commands++ }
            override fun openContextMenu(identity: SessionTabIdentity) { commands++ }
            override fun selectNewSession() { commands++ }
            override fun openSessions() { commands++ }
        }
        val scene = OffscreenScene.create(width = 320) {
            SessionTabBarContent(SessionTabBarState(tabs, null), callbacks, rememberComposeSessionTabBarRenderer(), {})
        }
        try {
            scene.settle { scene.hasDescription("Session tab: wheel-0") }
            fun first() = scene.nodes().firstOrNull {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Session tab: wheel-0") == true
            }
            val initial = assertNotNull(first()).boundsInWindow.left
            val point = assertNotNull(first()).boundsInWindow.center
            scene.scene.sendPointerEvent(PointerEventType.Move, point)
            repeat(4) { scene.scene.sendPointerEvent(PointerEventType.Scroll, point, scrollDelta = Offset(0f, 80f)) }
            repeat(100) { scene.advance(); delay(2) }
            assertTrue(first() == null || first()!!.boundsInWindow.left < initial)
            assertEquals(0, commands)
            repeat(6) { scene.scene.sendPointerEvent(PointerEventType.Scroll, point, scrollDelta = Offset(0f, -80f)) }
            scene.settle { scene.hasDescription("Session tab: wheel-0") }
            assertEquals(initial, assertNotNull(first()).boundsInWindow.left, 1f)
        } finally { scene.close() }
    } }

    test("vertical wheel remains unconsumed when tabs do not overflow") { offscreenTest {
        val tabs = listOf(SessionTabPresentation(SessionTabIdentity("only"), "Only"))
        var seen = 0
        var consumed = false
        val callbacks = noOpCallbacks()
        val scene = OffscreenScene.create(width = 320) {
            Box(Modifier.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        if (event.type == PointerEventType.Scroll) {
                            seen++
                            consumed = event.changes.any { it.isConsumed }
                        }
                    }
                }
            }) {
                SessionTabBarContent(SessionTabBarState(tabs, null), callbacks, rememberComposeSessionTabBarRenderer(), {})
            }
        }
        try {
            scene.settle { scene.hasDescription("Session tab: only") }
            val item = scene.nodes().first {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Session tab: only") == true
            }
            scene.scene.sendPointerEvent(PointerEventType.Scroll, item.boundsInWindow.center, scrollDelta = Offset(0f, 80f))
            assertEquals(1, seen)
            assertFalse(consumed)
        } finally { scene.close() }
    } }

    test("fractional wheel motion scales with density") { offscreenTest {
        val tabs = List(40) { SessionTabPresentation(SessionTabIdentity("density-$it"), "Density $it") }
        suspend fun movement(density: Float): Float {
            val scene = OffscreenScene.create(width = 600) {
                CompositionLocalProvider(LocalDensity provides Density(density)) {
                    SessionTabBarContent(
                        SessionTabBarState(tabs, null), noOpCallbacks(),
                        rememberComposeSessionTabBarRenderer(), {},
                    )
                }
            }
            try {
                scene.settle { scene.hasDescription("Session tab: density-1") }
                fun item() = scene.nodes().first {
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Session tab: density-1") == true
                }
                val before = item().boundsInWindow.left
                val bounds = item().boundsInWindow
                val point = bounds.center
                scene.scene.sendPointerEvent(PointerEventType.Move, point)
                repeat(4) {
                    scene.scene.sendPointerEvent(PointerEventType.Scroll, point, scrollDelta = Offset(0f, 7.5f))
                }
                repeat(80) { scene.advance(); delay(2) }
                return before - item().boundsInWindow.left
            } finally { scene.close() }
        }
        val one = movement(1f)
        val two = movement(2f)
        assertEquals(30f, one, 2f)
        assertEquals(60f, two, 2f)
    } }

    test("unmount discards queued input without later scrolling a new viewport") { offscreenTest {
        val tabs = List(40) { SessionTabPresentation(SessionTabIdentity("remount-$it"), "Remount $it") }
        var visible by mutableStateOf(true)
        var generation by mutableIntStateOf(0)
        val scene = OffscreenScene.create(width = 320) {
            if (visible) key(generation) {
                SessionTabBarContent(SessionTabBarState(tabs, null), noOpCallbacks(), rememberComposeSessionTabBarRenderer(), {})
            }
        }
        try {
            scene.settle { scene.hasDescription("Session tab: remount-0") }
            val first = scene.nodes().first {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Session tab: remount-0") == true
            }
            val initial = first.boundsInWindow.left
            val point = first.boundsInWindow.center
            scene.scene.sendPointerEvent(PointerEventType.Move, point)
            repeat(20) { scene.scene.sendPointerEvent(PointerEventType.Scroll, point, scrollDelta = Offset(0f, 60f)) }
            visible = false
            scene.settle { !scene.hasDescription("Session tab: remount-0") }
            generation++
            visible = true
            scene.settle { scene.hasDescription("Session tab: remount-0") }
            repeat(150) { scene.advance(); delay(2) }
            val remounted = scene.nodes().first {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Session tab: remount-0") == true
            }
            assertEquals(initial, remounted.boundsInWindow.left, 1f)
        } finally { scene.close() }
    } }

    test("RTL vertical and native horizontal wheel move in the same direction") { offscreenTest {
        val tabs = List(40) { SessionTabPresentation(SessionTabIdentity("rtl-$it"), "RTL $it") }
        suspend fun movement(delta: Offset): Float {
            val scene = OffscreenScene.create(width = 320) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    SessionTabBarContent(
                        SessionTabBarState(tabs, tabs[20].identity), noOpCallbacks(),
                        rememberComposeSessionTabBarRenderer(), {},
                    )
                }
            }
            try {
                scene.settle { scene.hasDescription("Session tab: rtl-20") }
                fun item() = scene.nodes().first {
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Session tab: rtl-20") == true
                }
                val before = item().boundsInWindow.left
                val point = item().boundsInWindow.center
                scene.scene.sendPointerEvent(PointerEventType.Move, point)
                scene.scene.sendPointerEvent(PointerEventType.Scroll, point, scrollDelta = delta)
                repeat(150) { scene.advance(); delay(2) }
                return item().boundsInWindow.left - before
            } finally { scene.close() }
        }
        val x = movement(Offset(15f, 0f))
        val y = movement(Offset(0f, 15f))
        assertTrue(abs(x) > 1f && abs(y) > 1f && x * y > 0f, "unexpected RTL movement: x=$x y=$y")
    } }

    test("rapid reversal does not leave queued forward wheel movement") { offscreenTest {
        val tabs = List(40) { SessionTabPresentation(SessionTabIdentity("burst-$it"), "Burst $it") }
        val scene = OffscreenScene.create(width = 320) {
            SessionTabBarContent(SessionTabBarState(tabs, null), noOpCallbacks(), rememberComposeSessionTabBarRenderer(), {})
        }
        try {
            scene.settle { scene.hasDescription("Session tab: burst-0") }
            fun first() = scene.nodes().firstOrNull {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Session tab: burst-0") == true
            }
            val initial = assertNotNull(first()).boundsInWindow.left
            val point = assertNotNull(first()).boundsInWindow.center
            scene.scene.sendPointerEvent(PointerEventType.Move, point)
            repeat(10) { scene.scene.sendPointerEvent(PointerEventType.Scroll, point, scrollDelta = Offset(0f, 30f)) }
            repeat(10) { scene.scene.sendPointerEvent(PointerEventType.Scroll, point, scrollDelta = Offset(0f, -30f)) }
            repeat(200) { scene.advance(); delay(2) }
            assertEquals(initial, assertNotNull(first()).boundsInWindow.left, 2f)
        } finally { scene.close() }
    } }

    test("a non-wheel mutation interrupts the queued wheel operation") { offscreenTest {
        val tabs = List(80) { SessionTabPresentation(SessionTabIdentity("interrupt-$it"), "Interrupt $it") }
        val scene = OffscreenScene.create(width = 320) {
            SessionTabBarContent(SessionTabBarState(tabs, null), noOpCallbacks(), rememberComposeSessionTabBarRenderer(), {})
        }
        try {
            scene.settle { scene.hasDescription("Session tab: interrupt-0") }
            val first = scene.nodes().first {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Session tab: interrupt-0") == true
            }
            val point = first.boundsInWindow.center
            scene.scene.sendPointerEvent(PointerEventType.Move, point)
            repeat(20) { scene.scene.sendPointerEvent(PointerEventType.Scroll, point, scrollDelta = Offset(0f, 40f)) }
            scene.scene.sendPointerEvent(PointerEventType.Scroll, point, scrollDelta = Offset(180f, 0f))
            repeat(200) { scene.advance(); delay(2) }
            val before = scene.nodes().filter {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { s -> s.startsWith("Session tab: interrupt-") } == true
            }.map { it.config.getOrNull(SemanticsProperties.ContentDescription) to it.boundsInWindow.left }
            repeat(100) { scene.advance(); delay(2) }
            val after = scene.nodes().filter {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { s -> s.startsWith("Session tab: interrupt-") } == true
            }.map { it.config.getOrNull(SemanticsProperties.ContentDescription) to it.boundsInWindow.left }
            assertEquals(before, after, "old queued wheel movement must eventually stop")
        } finally { scene.close() }
    } }

    test("native horizontal input supersedes an uncommitted vertical burst") { offscreenTest {
        val tabs = List(80) { SessionTabPresentation(SessionTabIdentity("takeover-$it"), "Takeover $it") }
        suspend fun run(withBurst: Boolean): Pair<List<String>, List<String>> {
            val scene = OffscreenScene.create(width = 320) {
                SessionTabBarContent(
                    SessionTabBarState(tabs, tabs[20].identity), noOpCallbacks(),
                    rememberComposeSessionTabBarRenderer(), {},
                )
            }
            try {
                scene.settle { scene.hasDescription("Session tab: takeover-20") }
                repeat(20) { scene.advance(); delay(2) }
                fun visible() = scene.nodes().flatMap {
                    it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                }.filter { it.startsWith("Session tab: takeover-") }.sorted()
                val before = visible()
                val first = scene.nodes().first {
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Session tab: takeover-20") == true
                }
                val point = first.boundsInWindow.center
                scene.scene.sendPointerEvent(PointerEventType.Move, point)
                if (withBurst) repeat(20) {
                    scene.scene.sendPointerEvent(PointerEventType.Scroll, point, scrollDelta = Offset(0f, 40f))
                }
                scene.scene.sendPointerEvent(PointerEventType.Scroll, point, scrollDelta = Offset(-180f, 0f))
                repeat(220) { scene.advance(); delay(2) }
                return before to visible()
            } finally { scene.close() }
        }
        val horizontalOnly = run(false)
        val afterTakeover = run(true)
        assertTrue(horizontalOnly.first.isNotEmpty())
        assertTrue(horizontalOnly.first != horizontalOnly.second, "horizontal input must actually move the viewport")
        assertEquals(horizontalOnly, afterTakeover, "old vertical burst must not reclaim the viewport after horizontal takeover")
    } }

    test("pointer press cancels old wheel input without disabling future scrolling") { offscreenTest {
        val tabs = List(80) { SessionTabPresentation(SessionTabIdentity("press-$it"), "Press $it") }
        val scene = OffscreenScene.create(width = 320) {
            SessionTabBarContent(
                SessionTabBarState(tabs, tabs[20].identity), noOpCallbacks(), rememberComposeSessionTabBarRenderer(), {},
            )
        }
        try {
            scene.settle { scene.hasDescription("Session tab: press-20") }
            repeat(20) { scene.advance(); delay(2) }
            fun visible() = scene.nodes().flatMap {
                it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            }.filter { it.startsWith("Session tab: press-") }.sorted()
            val initial = visible()
            val item = scene.nodes().first {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Session tab: press-20") == true
            }
            val point = item.boundsInWindow.center
            scene.scene.sendPointerEvent(PointerEventType.Move, point)
            repeat(20) { scene.scene.sendPointerEvent(PointerEventType.Scroll, point, scrollDelta = Offset(0f, 40f)) }
            scene.scene.sendPointerEvent(
                PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary,
            )
            scene.scene.sendPointerEvent(
                PointerEventType.Release, point, buttons = PointerButtons(), button = PointerButton.Primary,
            )
            repeat(220) { scene.advance(); delay(2) }
            assertEquals(initial, visible(), "a new pointer press must discard stale wheel input")
            scene.scene.sendPointerEvent(PointerEventType.Scroll, point, scrollDelta = Offset(0f, 180f))
            repeat(120) { scene.advance(); delay(2) }
            assertTrue(initial != visible(), "fresh wheel input must still work")
        } finally { scene.close() }
    } }

    test("selection change cancels old wheel input and preserves selected tail") { offscreenTest {
        val tabs = List(40) { SessionTabPresentation(SessionTabIdentity("selection-$it"), "Selection $it") }
        var state by mutableStateOf(SessionTabBarState(tabs, null))
        val callbacks = object : SessionTabBarCallbacks {
            override fun select(identity: SessionTabIdentity) {}
            override fun close(identity: SessionTabIdentity) {}
            override fun openContextMenu(identity: SessionTabIdentity) {}
            override fun selectNewSession() {}
            override fun openSessions() {}
        }
        val scene = OffscreenScene.create(width = 320) {
            SessionTabBarContent(state, callbacks, rememberComposeSessionTabBarRenderer(), {})
        }
        try {
            scene.settle { scene.hasDescription("Session tab: selection-0") }
            val first = scene.nodes().first {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Session tab: selection-0") == true
            }
            val point = first.boundsInWindow.center
            scene.scene.sendPointerEvent(PointerEventType.Move, point)
            repeat(20) { scene.scene.sendPointerEvent(PointerEventType.Scroll, point, scrollDelta = Offset(0f, 60f)) }
            state = SessionTabBarState(tabs, tabs.last().identity)
            fun selected() = scene.nodes().any {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Session tab: selection-39") == true &&
                    it.config.getOrNull(SemanticsProperties.Selected) == true
            }
            scene.settle { selected() }
            repeat(200) { scene.advance(); delay(2) }
            assertTrue(selected(), "old wheel input must not reclaim the selected tail")
        } finally { scene.close() }
    } }
}

private fun noOpCallbacks(): SessionTabBarCallbacks = object : SessionTabBarCallbacks {
    override fun select(identity: SessionTabIdentity) {}
    override fun close(identity: SessionTabIdentity) {}
    override fun openContextMenu(identity: SessionTabIdentity) {}
    override fun selectNewSession() {}
    override fun openSessions() {}
}
