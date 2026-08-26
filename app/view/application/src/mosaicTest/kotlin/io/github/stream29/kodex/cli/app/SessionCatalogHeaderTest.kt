package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.testing.runMosaicTest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionCatalogHeaderTest {
    @Test
    fun showArchivedIsTheTrailingTitleBarAction() = runTest {
        var showArchived by mutableStateOf(false)

        runMosaicTest {
            val initial = setContentAndSnapshot {
                SessionCatalogHeader(
                    showArchived = showArchived,
                    onShowArchivedChange = { showArchived = it },
                    modifier = Modifier.width(HeaderWidth),
                )
            }
            assertHeader(initial, "[ ] Show archived")

            sendKeyEvent(KeyboardEvent(codepoint = 13))
            assertHeader(awaitSnapshot(), "[x] Show archived")
            assertTrue(showArchived)
        }
    }
}

private fun assertHeader(snapshot: String, checkbox: String) {
    assertEquals(1, snapshot.lines().size, snapshot)
    assertEquals(HeaderWidth, snapshot.length, snapshot)
    assertTrue(snapshot.startsWith("Sessions"), snapshot)
    assertTrue(snapshot.endsWith(checkbox), snapshot)
}

private const val HeaderWidth: Int = 32
