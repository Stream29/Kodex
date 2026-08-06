package io.github.stream29.kodex.cli.app

import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import io.github.stream29.kodex.cli.components.rememberTuiDropdownState
import io.github.stream29.kodex.cli.newsession.NewSessionViewState
import io.github.stream29.kodex.cli.settings.KodexNewSessionSettings
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.openai.ModeKind
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.ServiceTier
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NewSessionSettingsTest {
    @Test
    fun virtualNewSessionDefaultsToItsCurrentSessionSettings() {
        assertEquals(
            SettingsRoute.Session,
            defaultSettingsRoute(SessionTabTarget.NewSession(id = 1, ordinal = 1)),
        )
        assertEquals(
            SettingsRoute.Global,
            defaultSettingsRoute(SessionTabTarget.OpenSession(sessionIndex = 1)),
        )
    }

    @Test
    fun currentNewSessionDraftRendersAllConfigurableFields() = runTest {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Column(Modifier.width(80)) {
                    NewSessionDraftSettingsContent(
                        state = NewSessionViewState(
                            settings = KodexNewSessionSettings(
                                model = OpenAiModelId("draft-model"),
                                reasoningEffort = ReasoningEffort.High,
                                serviceTier = ServiceTier.Fast,
                                mode = ModeKind.Plan,
                            ),
                            workingDirectory = Path("draft-workspace"),
                            newLineKey = NewLineKey.ShiftEnter,
                            codexHome = Path("codex-home"),
                        ),
                        dropdowns = SettingsDropdownStates(
                            model = rememberTuiDropdownState(),
                            reasoning = rememberTuiDropdownState(),
                            serviceTier = rememberTuiDropdownState(),
                            mode = rememberTuiDropdownState(),
                        ),
                        onBrowseWorkingDirectory = {},
                    )
                }
            }

            assertTrue("Working directory" in snapshot, snapshot)
            assertTrue("draft-workspace" in snapshot, snapshot)
            assertTrue("draft-model" in snapshot, snapshot)
            assertTrue("high" in snapshot, snapshot)
            assertTrue("fast" in snapshot, snapshot)
            assertTrue("plan" in snapshot, snapshot)
            assertFalse("No selected session" in snapshot, snapshot)
        }
    }
}
