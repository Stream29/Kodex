package io.github.stream29.codex.lite.cli.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.PasteEvent
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.codex.lite.cli.agent.ComposerViewModel
import io.github.stream29.codex.lite.cli.settings.NewLineKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class NewSessionScreenTest {
    @Test
    fun submitSurvivesReplacingTheVirtualNewSessionScreen() = runTest {
        val prompt = "Keep this first prompt."
        val composer = ComposerViewModel()
        val submitted = CompletableDeferred<String?>()

        runMosaicTest {
            setContentAndSnapshot {
                var showNewSession by remember { mutableStateOf(true) }
                val screenScope = rememberCoroutineScope()
                if (showNewSession) {
                    NewSessionScreen(
                        composerViewModel = composer,
                        columns = 80,
                        rows = 24,
                        newLineKey = NewLineKey.ShiftEnter,
                        onSubmit = {
                            screenScope.launch {
                                showNewSession = false
                                yield()
                                submitted.complete(composer.takeText())
                            }
                        },
                        statusBar = {},
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
            assertEquals(prompt, withUiRuntimeTimeout { submitted.await() })
        }
    }
}

private suspend fun <T> withUiRuntimeTimeout(block: suspend () -> T): T =
    withContext(Dispatchers.Default.limitedParallelism(1)) {
        withTimeout(5.seconds) { block() }
    }
