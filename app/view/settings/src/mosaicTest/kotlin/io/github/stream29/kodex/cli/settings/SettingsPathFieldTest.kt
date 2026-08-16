package io.github.stream29.kodex.cli.settings

import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsPathFieldTest {
    @Test
    fun titleAndBrowseActionShareTheHeaderRow() = runTest {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Column(Modifier.width(40)) {
                    SettingsPathField(
                        label = "Working directory",
                        value = "/workspace",
                        onBrowse = {},
                    )
                }
            }

            assertEquals(
                listOf("Working directory [Browse]", "/workspace"),
                snapshot.lines().map(String::trimEnd),
            )
        }
    }
}
