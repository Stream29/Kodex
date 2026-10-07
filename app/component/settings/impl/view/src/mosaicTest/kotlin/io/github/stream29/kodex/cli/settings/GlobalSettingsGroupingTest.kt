package io.github.stream29.kodex.cli.settings

import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import io.github.stream29.kodex.app.sessiontitlesettings.SessionTitleSettingsState
import io.github.stream29.kodex.app.sessiontitlesettings.SessionTitleSettingsViewModel
import io.github.stream29.kodex.app.applicationpreferences.ApplicationPreferencesState
import io.github.stream29.kodex.app.applicationpreferences.ApplicationPreferencesViewModel
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import kotlinx.coroutines.flow.MutableStateFlow
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals
import kotlin.test.assertTrue

val globalSettingsGroupingTest by testSuite {
    test("titleGenerationRendersAsItsOwnSection") {
        runMosaicTest {
            val titleModel = OpenAiModelId("title-model")
            val child = object : SessionTitleSettingsViewModel {
                override val state = MutableStateFlow(SessionTitleSettingsState(
                    true, null, titleModel, listOf(titleModel), ReasoningEffort.Low,
                ))
                override fun setEnabled(enabled: Boolean) {}
                override fun setModel(model: OpenAiModelId?) {}
                override fun setReasoningEffort(reasoningEffort: ReasoningEffort) {}
                override fun hidePage() {}
                override fun dismissFailure() {}
                override fun close() {}
            }
            val snapshot = setContentAndSnapshot {
                Column(Modifier.width(80)) {
                    SessionTitleSettingsPanel(child, rememberSessionTitleSettingsDropdowns())
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

    test("sidebarWidthSettingShowsColumnsAndUpdatesImmediately") {
        val updates = mutableListOf<Int>()
        // A static child projection preserves the original +/- snapshot contract: 27, then 29.
        val child = object : ApplicationPreferencesViewModel {
            override val state = MutableStateFlow(ApplicationPreferencesState(28, 28, NewLineKey.ShiftEnter))
            override fun setLeftWidth(columns: Int) { updates += columns }
            override fun setRightWidth(columns: Int) {}
            override fun setNewLineKey(newLineKey: NewLineKey) {}
            override fun setSubmitKey(submitKey: SubmitKey) {}
            override fun hidePage() {}
            override fun dismissFailure() {}
            override fun close() {}
        }
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Column(Modifier.width(80)) {
                    ApplicationPreferencesPanel(child, rememberApplicationPreferencesDropdowns())
                }
            }
            assertTrue("Left sidebar width [-][+]" in snapshot, snapshot)
            assertTrue("28 columns" in snapshot, snapshot)

            sendMouseEvent(MouseEvent(20, 1, MouseEvent.Type.Press, MouseEvent.Button.Left))
            awaitSnapshot()
            sendMouseEvent(MouseEvent(20, 1, MouseEvent.Type.Release))
            awaitSnapshot()
            sendMouseEvent(MouseEvent(23, 1, MouseEvent.Type.Press, MouseEvent.Button.Left))
            awaitSnapshot()
            sendMouseEvent(MouseEvent(23, 1, MouseEvent.Type.Release))
            awaitSnapshot()
        }

        assertEquals(listOf(27, 29), updates)
    }
}
