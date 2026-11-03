package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.ui.*
import kotlin.test.*

val offscreenCompactRolesTest by testSuite {
    for (role in UiActionRole.entries) {
        for (dark in listOf(false, true)) {
            test("compact $role dark=$dark preserves keyboard and latest disabled admission") { offscreenTest {
                var enabled by mutableStateOf(true)
                var calls = 0
                val scene = OffscreenScene.create(width = 320, height = 240) {
                    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                        UiAction("Command", { calls++ }, enabled, autoFocus = true, role = role)
                    }
                }
                try {
                    scene.settle { scene.hasText("Command") &&
                        scene.nodes().any { it.config.getOrNull(SemanticsProperties.Focused) == true } }
                    val button = scene.nodes().single { it.config.getOrNull(SemanticsProperties.Role) == Role.Button }
                    val expected = if (role == UiActionRole.Primary || role == UiActionRole.Secondary) 36f else 32f
                    assertEquals(expected, button.boundsInRoot.height)
                    val oldClick = button.config[SemanticsActions.OnClick].action!!
                    scene.key(Key.Spacebar)
                    scene.settle { calls == 1 }
                    enabled = false
                    scene.settle { scene.nodes().any { it.config.getOrNull(SemanticsProperties.Disabled) != null } }
                    oldClick()
                    scene.key(Key.Enter)
                    scene.frame()
                    assertEquals(1, calls)
                } finally { scene.close() }
            } }
        }
        test("compact $role retains full wrapping label at narrow enlarged font") { offscreenTest {
            val label = "A complete action 名称 with a long explanation"
            val scene = OffscreenScene.create(width = 180, height = 320) {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                    UiAction(label, {}, role = role)
                }
            }
            try {
                scene.settle { scene.hasText(label) }
                val bounds = scene.text(label).boundsInWindow
                assertTrue(bounds.width <= 180 && bounds.height > 24 && bounds.bottom <= 320)
            } finally { scene.close() }
        } }
    }
}
