package io.github.stream29.kodex.app.ui

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import de.infix.testBalloon.framework.core.testSuite
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals

val uiRendererDispatchTest by testSuite {
    test("widget defaults dispatch across the separately compiled renderer interface") {
        val renderer = RecordingUiRenderer()
        compose(renderer) {
            UiTheme {
                UiColumn {
                    UiText("text")
                    UiProse("prose")
                    UiActions { UiAction("action", {}) }
                    UiEditor("draft", 2, { _, _ -> null }, { _, _ -> })
                }
            }
        }
        assertEquals(
            listOf(
                "theme", "column", "text:text:Body:${Int.MAX_VALUE}", "prose:prose:Body",
                "actions", "action:action:true:false:Normal",
                "editor:draft:2::true:false:false:true:6:Enter:null",
            ),
            renderer.calls,
        )
    }
    test("explicit widget arguments reach the renderer without reverting to defaults") {
        val renderer = RecordingUiRenderer()
        compose(renderer) {
            UiText("error", UiTextRole.Error, 1)
            UiProse("detail", UiTextRole.Supporting)
            UiAction("delete", {}, enabled = false, autoFocus = true, role = UiActionRole.Destructive)
            UiEditor(
                "value", 3, { _, _ -> 9L }, { _, _ -> }, label = "Name", enabled = false,
                readOnly = true, autoFocus = true, multiline = false, maximumLines = 1,
                submitKey = UiSubmitKey.CtrlEnter, revision = 9L,
            )
        }
        assertEquals(
            listOf(
                "text:error:Error:1", "prose:detail:Supporting", "action:delete:false:true:Destructive",
                "editor:value:3:Name:false:true:true:false:1:CtrlEnter:9",
            ),
            renderer.calls,
        )
    }
}

private fun compose(renderer: UiRenderer, content: @Composable () -> Unit) {
    val recomposer = Recomposer(EmptyCoroutineContext)
    val composition = Composition(object : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = error("No rendering nodes are expected")
        override fun insertBottomUp(index: Int, instance: Unit) = error("No rendering nodes are expected")
        override fun remove(index: Int, count: Int) = error("No rendering nodes are expected")
        override fun move(from: Int, to: Int, count: Int) = error("No rendering nodes are expected")
        override fun onClear() {}
    }, recomposer)
    try {
        composition.setContent { WithUiRenderer(renderer, content) }
    } finally {
        composition.dispose()
        recomposer.cancel()
    }
}

private class RecordingUiRenderer : UiRenderer {
    val calls = mutableListOf<String>()
    @Composable override fun UiTheme(content: @Composable () -> Unit) { calls += "theme"; content() }
    @Composable override fun UiColumn(content: @Composable () -> Unit) { calls += "column"; content() }
    @Composable override fun UiActions(content: @Composable () -> Unit) { calls += "actions"; content() }
    @Composable override fun UiText(text: String, role: UiTextRole, maximumLines: Int) {
        calls += "text:$text:$role:$maximumLines"
    }
    @Composable override fun UiProse(text: String, role: UiTextRole) { calls += "prose:$text:$role" }
    @Composable override fun UiAction(label: String, onClick: () -> Unit, enabled: Boolean, autoFocus: Boolean, role: UiActionRole) {
        calls += "action:$label:$enabled:$autoFocus:$role"
    }
    @Composable override fun UiEditor(
        text: String, cursorOffset: Int, onChange: (String, Int) -> Long?,
        onSubmit: (text: String, cursorOffset: Int) -> Unit, label: String, enabled: Boolean,
        readOnly: Boolean, autoFocus: Boolean, multiline: Boolean, maximumLines: Int,
        submitKey: UiSubmitKey, revision: Long?,
    ) {
        calls += "editor:$text:$cursorOffset:$label:$enabled:$readOnly:$autoFocus:$multiline:$maximumLines:$submitKey:$revision"
    }
    @Composable override fun TextInputFrame(label: String, content: @Composable () -> Unit) { content() }
}
