@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.runtime.InternalComposeApi::class,
)

package io.github.stream29.kodex.app.ui.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.skiko.wasm.onWasmReady
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val rowMenuFocusRestorationTest by testSuite {
    test("invalidated old menu cannot steal focus while another overlay is active") {
        withContext(Dispatchers.Default) {
            val ready = CompletableDeferred<Unit>()
            onWasmReady { ready.complete(Unit) }
            withTimeout(10_000) { ready.await() }
            var open by mutableStateOf(true)
            var valid by mutableStateOf(true)
            var allowed by mutableStateOf(true)
            var focused = false
            val opening = Any()
            val scene = ImageComposeScene(320, 240, coroutineContext = Dispatchers.Unconfined)
            var time = 0L
            fun frame() { scene.render(++time * 16_666_667).close() }
            scene.setContent {
                MaterialTheme {
                    val fallback = remember { FocusRequester() }
                    val scope = rememberCoroutineScope()
                    val restoration = remember(scope) { RowMenuFocusRestoration(scope) }
                    TextButton({}, Modifier.focusRequester(fallback).onFocusChanged { focused = it.isFocused }) {
                        Text("Fallback")
                    }
                    if (open) restoration.Track(opening, fallback, { allowed }) { valid }
                }
            }
            try {
                repeat(3) { frame(); delay(1) }
                valid = false
                allowed = false
                open = false
                repeat(8) { frame(); delay(1) }
                assertFalse(focused, "A newer overlay must retain focus.")

                valid = true
                allowed = true
                open = true
                repeat(3) { frame(); delay(1) }
                valid = false
                open = false
                frame()
                delay(650)
                repeat(3) { frame(); delay(1) }
                assertFalse(focused, "A suspended old frame must not steal focus after a long delay.")

                valid = true
                open = true
                repeat(3) { frame(); delay(1) }
                valid = false
                allowed = true
                open = false
                withTimeout(5_000) {
                    while (!focused) { frame(); delay(1) }
                }
                assertTrue(focused, "A stale row with no newer overlay restores the fallback.")
            } finally {
                scene.close()
            }
        }
    }
}
