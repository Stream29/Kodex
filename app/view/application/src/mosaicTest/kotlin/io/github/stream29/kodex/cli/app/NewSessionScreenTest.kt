package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.PasteEvent
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.cli.settings.NewLineKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class NewSessionScreenTest {
    @Test
    fun submitCallbackRetainsTheExactComposerRevision() = runTest {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val prompt = "Keep this first prompt."
            val composer = fixture.newSession("New Session").composer
            var submittedRevision: Long? = null

            runMosaicTest {
                setContentAndSnapshot {
                    var showNewSession by remember { mutableStateOf(true) }
                    if (showNewSession) {
                        NewSessionContent(
                            composerViewModel = composer,
                            columns = 80,
                            rows = 23,
                            newLineKey = NewLineKey.ShiftEnter,
                            onSubmit = {
                                submittedRevision = composer.state.value.revision
                                showNewSession = false
                            },
                        )
                    } else {
                        Text("Root session")
                    }
                }

                sendPasteEvent(PasteEvent(prompt))
                awaitSnapshot()
                assertEquals(prompt, composer.state.value.text)

                sendKeyEvent(KeyboardEvent(13))
                awaitSnapshot()
                assertEquals(1, submittedRevision)
            }
        } finally {
            fixture.close()
        }
    }
}
