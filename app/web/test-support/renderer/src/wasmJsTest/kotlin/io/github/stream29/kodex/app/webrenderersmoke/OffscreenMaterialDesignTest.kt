package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.settings.contract.SettingsPage
import io.github.stream29.kodex.app.ui.*
import io.github.stream29.kodex.app.ui.compose.rememberComposeSettingsRootRenderer
import io.github.stream29.kodex.app.ui.compose.rememberComposeSettingsRenderer
import io.github.stream29.kodex.cli.settings.SettingsNavigationEntry
import kotlin.io.encoding.Base64
import kotlin.test.*

val offscreenMaterialDesignTest by testSuite {
    test("action hierarchy wraps at compact widths and keeps exact command admission") { offscreenTest {
        val calls = mutableListOf<String>()
        val scene = OffscreenScene.create(width = 320, height = 500) {
            UiActions {
                UiAction("Cancel", { calls += "cancel" })
                UiAction("Preview settings", { calls += "preview" }, role = UiActionRole.Secondary)
                UiAction("Save changes", { calls += "save" }, role = UiActionRole.Primary)
                UiAction("Remove", { calls += "remove" }, enabled = false, role = UiActionRole.Destructive)
            }
        }
        try {
            scene.settle { scene.hasText("Save changes") && scene.hasText("Remove") }
            for (label in listOf("Cancel", "Preview settings", "Save changes", "Remove")) {
                val bounds = scene.text(label).boundsInWindow
                assertTrue(bounds.left >= 0 && bounds.right <= 320 && bounds.height > 0, label)
            }
            assertTrue(scene.text("Remove").boundsInWindow.top > scene.text("Cancel").boundsInWindow.top)
            scene.click("Save changes")
            scene.click("Preview settings")
            scene.click("Cancel")
            val disabled = scene.nodes().first { it.config.contains(SemanticsProperties.Disabled) }
            disabled.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke()
            assertEquals(listOf("save", "preview", "cancel"), calls)
            println("KODEX_DESIGN_PNG actions-compact ${Base64.encode(scene.frame())}")
        } finally { scene.close() }
    } }
    for (width in listOf(1200, 420)) {
        test("settings navigation and labeled fields remain bounded at ${width}px") { offscreenTest {
            var page by mutableStateOf(SettingsPage.General)
            var draft by mutableStateOf("My workspace")
            var dismissals = 0
            val scene = OffscreenScene.create(width = width, height = 900) {
                val root = rememberComposeSettingsRootRenderer()
                val fields = rememberComposeSettingsRenderer()
                root.Frame(
                    page, listOf(
                        SettingsNavigationEntry(SettingsPage.General, "General"),
                        SettingsNavigationEntry(SettingsPage.Hooks, "Hooks"),
                        SettingsNavigationEntry(SettingsPage.Mcp, "MCP servers"),
                    ),
                    { page = it }, { dismissals++ }, {},
                ) {
                    fields.Section("Workspace preferences") {
                        UiText("Personalize how this workspace behaves.", UiTextRole.Supporting)
                        UiTextInput(draft, { draft = it }, "Workspace name")
                        fields.Toggle("Show supporting details", true, {}, true, "Keep additional context visible.")
                    }
                    UiActions {
                        UiAction("Reset", {})
                        UiAction("Save changes", {}, role = UiActionRole.Primary)
                    }
                }
            }
            try {
                scene.settle { scene.hasText("Workspace preferences") && scene.hasText("Close") }
                val navigation = scene.nodes().first {
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Settings page General") == true
                }.boundsInWindow
                val content = scene.text("Workspace preferences").boundsInWindow
                assertTrue(content.left >= 0 && content.right <= width)
                if (width >= 720) assertTrue(navigation.right <= content.left, "Wide navigation must sit beside the page.")
                else assertTrue(navigation.bottom <= content.top, "Compact navigation must sit above the page.")
                println("KODEX_DESIGN_PNG settings-$width ${Base64.encode(scene.frame())}")
                scene.clickDescription("Settings page Hooks")
                scene.settle { page == SettingsPage.Hooks }
                scene.click("Close")
                assertEquals(1, dismissals)
            } finally { scene.close() }
        } }
    }
}
