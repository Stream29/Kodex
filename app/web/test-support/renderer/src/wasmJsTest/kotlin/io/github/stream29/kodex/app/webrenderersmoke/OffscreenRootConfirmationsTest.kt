package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.application.contract.*
import io.github.stream29.kodex.app.sessiondelete.createSessionDeleteViewModel
import io.github.stream29.kodex.app.sessiondelete.SessionDeleteContent
import io.github.stream29.kodex.app.sessiondelete.contract.SessionDeleteDependencies
import io.github.stream29.kodex.app.ui.compose.rememberComposeApplicationMenuRenderer
import io.github.stream29.kodex.app.ui.compose.rememberComposeApplicationPopupRenderer
import io.github.stream29.kodex.cli.app.ApplicationPopupContent
import io.github.stream29.kodex.app.ui.UiText
import io.github.stream29.kodex.app.ui.UiTextRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

val offscreenRootConfirmationsTest by testSuite {
    for (revert in listOf(false, true)) {
        test("root confirmation is strictly shorter than original revert=$revert") { offscreenTest {
            suspend fun height(compact: Boolean): Float {
                val child = createSessionDeleteViewModel(3, "Target", SessionDeleteDependencies { false })
                val title = if (revert) "Revert history" else "Delete Session"
                val scene = OffscreenScene.create(width = 1024, height = 900) {
                    if (compact) {
                        if (revert) rememberComposeApplicationMenuRenderer().RevertConfirmation(
                            title, listOf("Remove later history."), "Cancel", "Revert", {}, {},
                        ) else rememberComposeApplicationPopupRenderer { false }.Delete(child) {}
                    } else Dialog({}) {
                        Surface(Modifier.semantics { paneTitle = title }, shape = MaterialTheme.shapes.extraLarge) {
                            Column(Modifier.widthIn(max = 560.dp).padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                if (revert) {
                                    Text(title, style = MaterialTheme.typography.titleLarge)
                                    Text("Remove later history.")
                                    Row {
                                        TextButton({}) { Text("Cancel") }
                                        TextButton({}) { Text("Revert") }
                                    }
                                } else {
                                    UiText(title, UiTextRole.Title)
                                    SessionDeleteContent(child, {}, {}) { it() }
                                }
                            }
                        }
                    }
                }
                return try {
                    scene.settle { scene.hasText("Cancel") }
                    scene.nodes().single { it.config.getOrNull(SemanticsProperties.PaneTitle) == title }.boundsInRoot.height
                } finally { scene.close(); child.close() }
            }
            val compact = height(true)
            val original = height(false)
            assertTrue(compact < original, "$compact must be shorter than $original")
        } }
    }
    for ((width, height) in listOf(1024 to 768, 360 to 640, 320 to 240)) {
        for (scale in listOf(1f, 1.5f)) {
            test("root Delete safe focus and exact child ${width}x$height font=$scale") { offscreenTest {
                val deletions = mutableListOf<Int>()
                val child = createSessionDeleteViewModel(72, "A very long session 名称 ".repeat(15),
                    SessionDeleteDependencies { deletions += it; true })
                var closes = 0
                val scene = OffscreenScene.create(width = width, height = height) {
                    CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                        rememberComposeApplicationPopupRenderer { false }.Delete(child) { closes++ }
                    }
                }
                try {
                    scene.settle { scene.hasText("Cancel") && scene.text("Cancel").boundsInWindow.height > 0 &&
                        scene.nodes().any { it.config.getOrNull(SemanticsProperties.Focused) == true &&
                            it.children.any { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Cancel" } == true } } }
                    assertTrue(scene.text("Cancel").boundsInWindow.bottom <= height)
                    scene.key(Key.Enter)
                    scene.settle { closes == 1 }
                    assertTrue(deletions.isEmpty())
                    assertTrue(child.isActive)
                } finally { scene.close() }
                assertFalse(child.isActive)
            } }
            test("Revert safe focus and reachable danger ${width}x$height font=$scale") { offscreenTest {
                var cancels = 0
                var confirms = 0
                val scene = OffscreenScene.create(width = width, height = height) {
                    CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                        rememberComposeApplicationMenuRenderer().RevertConfirmation(
                            "Revert history", listOf("Full history consequences 内容 ".repeat(50)),
                            "Cancel", "Revert", { cancels++ }, { confirms++ },
                        )
                    }
                }
                try {
                    scene.settle { scene.hasText("Cancel") && scene.text("Cancel").boundsInWindow.height > 0 &&
                        scene.nodes().any { it.config.getOrNull(SemanticsProperties.Focused) == true &&
                            it.children.any { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Cancel" } == true } } }
                    scene.key(Key.Enter)
                    assertEquals(1, cancels)
                    assertEquals(0, confirms)
                    val revert = scene.nodes().first { n -> n.children.any {
                        it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Revert" } == true
                    } && n.config.getOrNull(SemanticsActions.RequestFocus) != null }
                    assertTrue(revert.config[SemanticsActions.RequestFocus].action!!.invoke())
                    scene.settle { scene.text("Revert").boundsInWindow.height > 0 }
                    scene.key(Key.Enter)
                    assertEquals(1, confirms)
                } finally { scene.close() }
            } }
        }
    }
    for (result in listOf(false, true)) {
        test("root Delete dismisses captured opening after result=$result") { offscreenTest {
            val deleted = mutableListOf<Int>()
            val child = createSessionDeleteViewModel(91, "Original target", SessionDeleteDependencies { deleted += it; result })
            val opening = ApplicationPopupState.DeleteSession(child)
            val app = RootDeleteApplication(opening)
            val scene = OffscreenScene.create {
                ApplicationPopupContent(app, app.popup.collectAsState().value,
                    rememberComposeApplicationPopupRenderer { false }, { throw it }, { false })
            }
            try {
                scene.settle { scene.hasText("Delete") }
                scene.click("Delete")
                scene.settle { app.popup.value === ApplicationPopupState.Closed }
                assertEquals(listOf(91), deleted)
                assertSame(opening, app.dismissed.single())
                assertFalse(child.isActive)
            } finally { scene.close() }
        } }
    }
}

private class RootDeleteApplication(initial: ApplicationPopupState) :
    ApplicationViewModel by NavigationApplication(emptyList()) {
    override val popup = MutableStateFlow(initial)
    val dismissed = mutableListOf<ApplicationPopupState.Open>()
    override fun dismissPopup(expected: ApplicationPopupState.Open): Boolean {
        dismissed += expected
        if (popup.value !== expected) return false
        popup.value = ApplicationPopupState.Closed
        return true
    }
}
