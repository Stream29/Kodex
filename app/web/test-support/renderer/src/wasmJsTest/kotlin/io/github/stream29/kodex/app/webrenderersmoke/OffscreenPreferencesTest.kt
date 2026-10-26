@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.stream29.kodex.app.webrenderersmoke

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.applicationpreferences.*
import io.github.stream29.kodex.app.ui.compose.rememberComposeSettingsRenderer
import io.github.stream29.kodex.cli.settings.ApplicationPreferencesContent
import io.github.stream29.kodex.cli.settings.NewLineKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

val offscreenPreferencesTest by testSuite {
    test("shared General controls resize captured widths and both menus write one canonical key") {
        preferencesScene { ports, _, scene ->
            assertTrue(scene.text("Sidebars").boundsInRoot.top < scene.text("Input").boundsInRoot.top)
            assertTrue(ports.resizes.isEmpty() && ports.keys.isEmpty())
            scene.click("-")
            scene.settle { scene.hasText("27 columns") }
            assertEquals(27 to 24, ports.resizes.single())
            scene.click("+", occurrence = 1)
            scene.settle { scene.hasText("25 columns") }
            assertEquals(27 to 25, ports.resizes.last())

            scene.clickDescription("New line key: Shift+Enter")
            scene.settle { scene.hasDescription("New line key: Enter") }
            scene.clickDescription("New line key: Enter")
            scene.settle { scene.hasDescription("Submit key: Ctrl+Enter") }
            assertEquals(listOf(NewLineKey.Enter), ports.keys)
            scene.clickDescription("Submit key: Ctrl+Enter")
            scene.settle { scene.hasDescription("Submit key: Enter") }
            scene.clickDescription("Submit key: Enter")
            scene.settle { scene.hasDescription("New line key: Shift+Enter") }
            assertEquals(listOf(NewLineKey.Enter, NewLineKey.ShiftEnter), ports.keys)
            scene.frame()
        }
    }
    test("minimum and maximum disable only their exact width action without correcting source") {
        preferencesScene(0 to Int.MAX_VALUE) { ports, _, scene ->
            assertTrue(scene.hasText("4 columns"))
            scene.click("-")
            scene.click("+", occurrence = 1)
            assertTrue(ports.resizes.isEmpty())
            assertEquals(0 to Int.MAX_VALUE, ports.widths.value)
            scene.click("+")
            scene.settle { scene.hasText("5 columns") }
            assertEquals(5 to Int.MAX_VALUE, ports.resizes.single())
        }
    }
    test("Escape dismisses a graphical menu and failure acknowledgment neither writes nor closes its child") {
        preferencesScene { ports, child, scene ->
            scene.clickDescription("New line key: Shift+Enter")
            scene.settle { scene.hasDescription("New line key: Enter") }
            scene.escape()
            scene.settle { !scene.hasDescription("New line key: Enter") }
            assertTrue(ports.keys.isEmpty())
            ports.operationFailure.value = true
            scene.settle { scene.hasText("A settings operation failed.") }
            scene.click("Dismiss")
            scene.settle { !scene.hasText("A settings operation failed.") }
            assertEquals(1, ports.dismissals)
            assertTrue(ports.resizes.isEmpty() && ports.keys.isEmpty())
            assertFalse(child.state.value.closed)
        }
    }
}

private suspend fun preferencesScene(
    widths: Pair<Int, Int> = 28 to 24,
    block: suspend (PreferencePorts, ApplicationPreferencesViewModel, OffscreenScene) -> Unit,
) = offscreenTest {
    val ports = PreferencePorts(widths)
    val owner = CoroutineScope(currentCoroutineContext() + Job(currentCoroutineContext()[Job]))
    val child = createApplicationPreferencesViewModel(ports, owner)
    var scene: OffscreenScene? = null
    try {
        val rendered = OffscreenScene.create {
            ApplicationPreferencesContent(child, rememberComposeSettingsRenderer())
        }.also { scene = it }
        rendered.settle { rendered.hasDescription("New line key: Shift+Enter") }
        block(ports, child, rendered)
        rendered.close()
        scene = null
        assertFalse(child.state.value.closed)
    } finally {
        scene?.close()
        child.close()
        owner.coroutineContext.job.cancelAndJoin()
    }
}

internal class PreferencePorts(widths: Pair<Int, Int>) : ApplicationPreferencesDependencies {
    override val widths = MutableStateFlow(widths)
    override val newLineKey = MutableStateFlow(NewLineKey.ShiftEnter)
    override val operationFailure = MutableStateFlow(false)
    val resizes = mutableListOf<Pair<Int, Int>>()
    val keys = mutableListOf<NewLineKey>()
    var dismissals = 0
    override fun setLeftWidth(columns: Int) {
        widths.value = columns to widths.value.second
        resizes += widths.value
    }
    override fun setRightWidth(columns: Int) {
        widths.value = widths.value.first to columns
        resizes += widths.value
    }
    override fun setNewLineKey(newLineKey: NewLineKey): PreferencesWriteAdmission {
        keys += newLineKey
        this.newLineKey.value = newLineKey
        return PreferencesWriteAdmission.Accepted
    }
    override fun reportFailure(failure: Throwable) { operationFailure.value = true }
    override fun dismissFailure() { dismissals++; operationFailure.value = false }
}
