package io.github.stream29.kodex.cli.settings

import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import io.github.stream29.kodex.app.settings.contract.GlobalSettingsState
import io.github.stream29.kodex.cli.components.rememberTuiDropdownState
import io.github.stream29.kodex.openai.OpenAiModelId
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlobalSettingsGroupingTest {
    @Test
    fun titleGenerationRendersAsItsOwnSection() = runTest {
        runMosaicTest {
            val titleModel = OpenAiModelId("title-model")
            val snapshot = setContentAndSnapshot {
                Column(Modifier.width(80)) {
                    SessionTitleSettingsContent(
                        state = GlobalSettingsState(
                            settingsRevision = 0,
                            codexHome = Path("/codex-home"),
                            authSource = KodexAuthSource.Codex,
                            newLineKey = NewLineKey.ShiftEnter,
                            sessionTitle = SessionTitleSettings(),
                            sidebars = SidebarSettings(),
                            effectiveSessionTitleModel = titleModel,
                            modelOptions = listOf(titleModel),
                        ),
                        modelDropdown = rememberTuiDropdownState(),
                        reasoningDropdown = rememberTuiDropdownState(),
                        onUpdateEnabled = {},
                    )
                }
            }

            val section = snapshot.indexOf("Title generation")
            val titleGeneration = snapshot.indexOf("[x] Automatic session title")
            val model = snapshot.indexOf("Title model")
            val reasoning = snapshot.indexOf("Title reasoning")
            assertTrue(section >= 0, snapshot)
            assertTrue(section < titleGeneration, snapshot)
            assertTrue(titleGeneration < model, snapshot)
            assertTrue(model < reasoning, snapshot)
        }
    }

    @Test
    fun sidebarWidthSettingShowsColumnsAndUpdatesImmediately() = runTest {
        val updates = mutableListOf<Int>()
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Column(Modifier.width(80)) {
                    SettingsSidebarWidthItem(
                        label = "Left sidebar width",
                        columns = 28,
                        onChange = updates::add,
                    )
                }
            }
            assertTrue("Left sidebar width [-][+]" in snapshot, snapshot)
            assertTrue("28 columns" in snapshot, snapshot)

            sendMouseEvent(MouseEvent(20, 0, MouseEvent.Type.Press, MouseEvent.Button.Left))
            awaitSnapshot()
            sendMouseEvent(MouseEvent(20, 0, MouseEvent.Type.Release))
            awaitSnapshot()
            sendMouseEvent(MouseEvent(23, 0, MouseEvent.Type.Press, MouseEvent.Button.Left))
            awaitSnapshot()
            sendMouseEvent(MouseEvent(23, 0, MouseEvent.Type.Release))
            awaitSnapshot()
        }

        assertEquals(listOf(27, 29), updates)
    }
}
