package io.github.stream29.kodex.cli.sessionrename

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.app.sessionrename.SessionRenameContent
import io.github.stream29.kodex.app.sessionrename.contract.SessionRenameDependencies
import io.github.stream29.kodex.app.sessionrename.createSessionRenameViewModel
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val sessionRenameEditorTest by testSuite {
    test("plainEnterSubmitsWithoutAnActionButton") {
        var submissions by mutableStateOf(0)
        val child = createSessionRenameViewModel("Session title", SessionRenameDependencies { submissions += 1 })

        runMosaicTest {
            val initial = setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                Column {
                    SessionRenameContent(child, onSubmitted = {})
                    Text("submissions=$submissions")
                }
            }
            }
            assertTrue("Rename session" in initial, initial)
            assertFalse("[Rename]" in initial, initial)
            assertFalse("[Cancel]" in initial, initial)

            sendKeyEvent(
                KeyboardEvent(
                    codepoint = 13,
                    modifiers = KeyboardEvent.ModifierShift,
                ),
            )
            assertEquals(0, submissions)

            sendKeyEvent(KeyboardEvent(codepoint = 13))
            val submitted = awaitSnapshot()
            assertTrue("submissions=1" in submitted, submitted)
        }

        assertEquals(1, submissions)
    }
}
