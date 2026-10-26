@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.sessiontabbar.contract.*
import io.github.stream29.kodex.app.ui.compose.rememberComposeSessionTabBarRenderer
import kotlin.test.*

val offscreenSessionTabBarTest by testSuite {
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
