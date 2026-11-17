package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.ui.*
import kotlin.test.*

val offscreenTextInputRendererTest by testSuite {
    test("a value-field renderer is scoped and keeps the owning draft and submit callbacks") { offscreenTest {
        var value by mutableStateOf("owner")
        var edit: ((String) -> Unit)? = null
        var submit: (() -> Unit)? = null
        var submissions = 0
        val labels = mutableListOf<String>()
        val renderer = object : UiTextInputRenderer {
            @Composable
            override fun Field(
                text: String, onChange: (String) -> Unit, label: String,
                enabled: Boolean, autoFocus: Boolean, onSubmit: () -> Unit,
            ) {
                assertTrue(enabled)
                assertFalse(autoFocus)
                labels += label
                edit = onChange
                submit = onSubmit
                UiText("Scoped value: $text")
            }
        }
        val scene = OffscreenScene.create {
            WithUiTextInputRenderer(renderer) {
                UiTextInput(value, { value = it }, "scoped", onSubmit = { submissions++ })
            }
            UiTextInput("outside", {}, "raster sibling")
        }
        try {
            scene.settle { scene.hasText("Scoped value: owner") && scene.hasDescription("raster sibling") }
            assertTrue(labels.isNotEmpty() && labels.all { it == "scoped" })
            assertEquals(1, scene.nodes().count { it.config.getOrNull(SemanticsActions.SetText) != null })
            assertNotNull(edit)("latest\n中文")
            assertEquals("latest\n中文", value)
            assertNotNull(submit)()
            assertEquals(1, submissions)
            scene.settle { scene.hasText("Scoped value: latest\n中文") }
            scene.frame()
        } finally { scene.close() }
    } }
}
