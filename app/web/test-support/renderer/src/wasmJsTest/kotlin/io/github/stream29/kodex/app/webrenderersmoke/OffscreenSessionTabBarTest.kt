@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.sessiontabbar.contract.*
import io.github.stream29.kodex.app.ui.compose.rememberComposeSessionTabBarRenderer
import kotlin.test.*

val offscreenSessionTabBarTest by testSuite {
    for (key in listOf(Key.Tab, Key.DirectionRight)) {
        test("natural tab navigation reaches beyond bounds and returns across fixed controls key=$key") { offscreenTest {
            val tabs = List(18) { SessionTabPresentation(SessionTabIdentity("nav-$it"), "Navigation tab $it") }
            val calls = mutableListOf<String>()
            val scene = OffscreenScene.create(width = 480, height = 100) {
                SessionTabBarContent(SessionTabBarState(tabs, tabs.first().identity), TabCommands("nav", calls),
                    rememberComposeSessionTabBarRenderer())
            }
            fun focused(description: String) = scene.nodes().any {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true &&
                    it.config.getOrNull(SemanticsProperties.Focused) == true
            }
            try {
                scene.settle { scene.hasDescription("Sessions") }
                scene.key(Key.Tab)
                scene.settle { focused("Sessions") }
                for (index in tabs.indices) {
                    scene.key(key)
                    scene.settle("natural traversal tab $index") { focused("Session tab: nav-$index") }
                }
                scene.key(key)
                scene.settle { focused("New Session") }
                scene.key(Key.Enter)
                assertEquals(listOf("nav:new"), calls)
                for (index in tabs.indices.reversed()) {
                    scene.key(if (key == Key.Tab) Key.Tab else Key.DirectionLeft, shift = key == Key.Tab)
                    scene.settle("reverse natural traversal tab $index") { focused("Session tab: nav-$index") }
                }
                scene.key(if (key == Key.Tab) Key.Tab else Key.DirectionLeft, shift = key == Key.Tab)
                scene.settle { focused("Sessions") }
            } finally { scene.close() }
        } }
    }

    test("tab bar pages horizontally by its viewport and ignores modified page keys") { offscreenTest {
        val tabs = List(40) { SessionTabPresentation(SessionTabIdentity("page-$it"), "Paging tab $it") }
        val scene = OffscreenScene.create(width = 480, height = 100) {
            SessionTabBarContent(SessionTabBarState(tabs, tabs.first().identity), TabCommands("page", mutableListOf()),
                rememberComposeSessionTabBarRenderer())
        }
        fun scroll() = scene.nodes().mapNotNull { it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) }.single().value()
        try {
            scene.settle { scene.hasDescription("Session tab: page-0") }
            scene.key(Key.Tab)
            val before = scroll()
            scene.key(Key.PageDown, shift = true)
            scene.advance()
            assertEquals(before, scroll())
            scene.key(Key.PageDown)
            scene.settle { scroll() > before }
            scene.key(Key.PageUp)
            scene.settle { scroll() == before }
        } finally { scene.close() }
    } }

    test("session navigation consumes the production label role") { offscreenTest {
        val tab = SessionTabPresentation(SessionTabIdentity("typography"), "Navigation 漢字")
        val scene = OffscreenScene.create(width = 800) {
            io.github.stream29.kodex.app.ui.UiTheme {
                SessionTabBarContent(
                    SessionTabBarState(listOf(tab), tab.identity),
                    TabCommands("typography", mutableListOf()), rememberComposeSessionTabBarRenderer(), {},
                )
            }
        }
        try {
            scene.settle { scene.hasText(tab.label) }
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            assertTrue(assertNotNull(scene.text(tab.label).config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)(layouts))
            val style = layouts.single().layoutInput.style
            assertEquals(14.sp, style.fontSize)
            assertEquals(20.sp, style.lineHeight)
            assertEquals(androidx.compose.ui.text.font.FontWeight.Medium, style.fontWeight)
        } finally { scene.close() }
    } }

    test("narrow compact tabs retain exact tail selection full names and keyboard menu activation") { offscreenTest {
        val fullName = "完整会话名称 " + "Long name ".repeat(25)
        val tabs = List(80) { SessionTabPresentation(SessionTabIdentity("narrow-$it"), fullName + it, running = it == 79) }
        val state = SessionTabBarState(tabs, tabs.last().identity)
        val commands = mutableListOf<String>()
        val menus = mutableListOf<SessionTabMenuRequest>()
        val scene = OffscreenScene.create(width = 160) {
            SessionTabBarContent(state, TabCommands("narrow", commands), rememberComposeSessionTabBarRenderer(), menus::add)
            TextButton({}) { Text("Baseline") }
        }
        try {
            scene.settle { scene.hasDescription("Session tab: narrow-79") && scene.hasText("Baseline") }
            val tail = scene.nodes().single {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Session tab: narrow-79") == true
            }
            assertEquals(Role.Tab, tail.config.getOrNull(SemanticsProperties.Role))
            assertEquals("Running", tail.config.getOrNull(SemanticsProperties.StateDescription))
            assertTrue(tail.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(fullName + 79))
            assertTrue(tail.boundsInWindow.width > 0)
            assertTrue(tail.boundsInWindow.left >= 0 && tail.boundsInWindow.right <= 160)
            val baseline = scene.nodes().first { it.config.getOrNull(SemanticsActions.OnClick) != null &&
                it.children.any { child -> child.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Baseline" } == true }
            }
            assertTrue(tail.boundsInRoot.height < baseline.boundsInRoot.height)
            assertTrue(assertNotNull(tail.config.getOrNull(SemanticsActions.RequestFocus)?.action).invoke())
            scene.key(Key.Spacebar)
            scene.settle { commands.contains("narrow:select:narrow-79") }
            scene.key(Key.F10, shift = true)
            scene.settle { menus.size == 1 }
            assertEquals(tabs.last().identity, menus.single().identity)
            assertEquals(fullName + 79, menus.single().label)
            scene.clickDescription("Sessions")
            scene.clickDescription("New Session")
            assertEquals(listOf("narrow:sessions", "narrow:new"), commands.takeLast(2))
            scene.frame()
        } finally { scene.close() }
    } }

    test("duplicate labels and reordered tabs keep exact identity and reject removed captured callbacks") { offscreenTest {
        val first = SessionTabPresentation(SessionTabIdentity("first"), "Same")
        val second = SessionTabPresentation(SessionTabIdentity("second"), "Same", running = true)
        var state by mutableStateOf(SessionTabBarState(listOf(first, second), second.identity))
        var tag by mutableStateOf("old")
        val commands = mutableListOf<String>()
        val menus = mutableListOf<SessionTabMenuRequest>()
        var oldSelect: (() -> Unit)? = null
        var oldMenu: ((SessionTabMenuPlacement) -> Unit)? = null
        var renderedTag = ""
        val scene = OffscreenScene.create {
            val graphical = rememberComposeSessionTabBarRenderer()
            val renderer = object : SessionTabBarRenderer by graphical {
                @Composable
                override fun Tab(
                    tab: SessionTabPresentation, selected: Boolean, first: Boolean,
                    onSelect: () -> Unit, onMenu: (SessionTabMenuPlacement) -> Unit,
                ) {
                    if (tab.identity.value == "first" && oldSelect == null) { oldSelect = onSelect; oldMenu = onMenu }
                    graphical.Tab(tab, selected, first, onSelect, onMenu)
                }
            }
            renderedTag = tag
            SessionTabBarContent(state, TabCommands(tag, commands), renderer, menus::add)
        }
        try {
            scene.settle { scene.hasDescription("Session tab: second") && oldSelect != null }
            assertTrue(scene.tabSelected("second"))
            assertFalse(scene.tabSelected("first"))
            scene.clickDescription("Session tab: second")
            assertEquals(listOf("old:select:second"), commands)
            scene.secondaryTab("second")
            scene.settle { menus.size == 1 }
            assertEquals(second.identity, menus.single().identity)
            assertEquals("Same", menus.single().label)
            assertEquals("old:menu:second", commands.last())
            scene.secondaryTab("second")
            scene.settle { menus.size == 2 }
            assertNotEquals(menus[0], menus[1])
            tag = "new"
            state = SessionTabBarState(listOf(second.copy(label = "Renamed"), first), first.identity)
            scene.settle { renderedTag == "new" && scene.hasText("Renamed") && scene.tabSelected("first") }
            assertNotNull(oldSelect).invoke()
            assertEquals("new:select:first", commands.last())
            assertTrue(scene.text("Renamed").boundsInWindow.left < scene.text("Same").boundsInWindow.left)
            scene.click("Sessions")
            scene.clickDescription("New Session")
            assertEquals(listOf("new:sessions", "new:new"), commands.takeLast(2))
            state = SessionTabBarState(listOf(second), null)
            scene.settle { !scene.hasDescription("Session tab: first") }
            val previous = commands.toList()
            val placement = object : SessionTabMenuPlacement {}
            assertNotNull(oldSelect).invoke()
            assertNotNull(oldMenu).invoke(placement)
            assertEquals(previous, commands)
            assertEquals(2, menus.size)
            scene.frame()
        } finally { scene.close() }
    } }
    test("a thousand graphical tabs compose only a viewport and reveal the exact selected tail") { offscreenTest {
        val tabs = List(1_000) { SessionTabPresentation(SessionTabIdentity("tab-$it"), "Tab $it") }
        var state by mutableStateOf(SessionTabBarState(tabs, tabs.last().identity))
        val active = mutableSetOf<SessionTabIdentity>()
        var peak = 0
        val commands = mutableListOf<String>()
        val scene = OffscreenScene.create {
            val graphical = rememberComposeSessionTabBarRenderer()
            val renderer = object : SessionTabBarRenderer by graphical {
                @Composable
                override fun Tab(
                    tab: SessionTabPresentation, selected: Boolean, first: Boolean,
                    onSelect: () -> Unit, onMenu: (SessionTabMenuPlacement) -> Unit,
                ) {
                    DisposableEffect(tab.identity) {
                        active += tab.identity; peak = maxOf(peak, active.size)
                        onDispose { active -= tab.identity }
                    }
                    graphical.Tab(tab, selected, first, onSelect, onMenu)
                }
            }
            SessionTabBarContent(state, TabCommands("lazy", commands), renderer)
        }
        try {
            scene.settle { scene.hasDescription("Session tab: tab-999") && scene.tabSelected("tab-999") }
            assertTrue(active.size in 1..20, "active=$active")
            assertTrue(peak <= 30, "peak=$peak")
            scene.clickDescription("Session tab: tab-999")
            assertEquals(listOf("lazy:select:tab-999"), commands)
            state = SessionTabBarState(emptyList(), null)
            scene.settle { active.isEmpty() }
            assertTrue(scene.hasText("Sessions"))
            scene.clickDescription("New Session")
            assertEquals("lazy:new", commands.last())
            scene.frame()
        } finally { scene.close() }
        assertTrue(active.isEmpty())
    } }
}

private class TabCommands(private val tag: String, private val commands: MutableList<String>) : SessionTabBarCallbacks {
    override fun select(identity: SessionTabIdentity) { commands += "$tag:select:${identity.value}" }
    override fun close(identity: SessionTabIdentity) { commands += "$tag:close:${identity.value}" }
    override fun openContextMenu(identity: SessionTabIdentity) { commands += "$tag:menu:${identity.value}" }
    override fun selectNewSession() { commands += "$tag:new" }
    override fun openSessions() { commands += "$tag:sessions" }
}

private fun OffscreenScene.tabSelected(id: String) = nodes().any {
    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Session tab: $id") == true &&
        it.config.getOrNull(SemanticsProperties.Selected) == true
}

private fun OffscreenScene.secondaryTab(id: String) {
    val node = nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Session tab: $id") == true }
    val point = node.boundsInWindow.center
    scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isSecondaryPressed = true), button = PointerButton.Secondary)
    scene.sendPointerEvent(PointerEventType.Release, point, buttons = PointerButtons(), button = PointerButton.Secondary)
}
