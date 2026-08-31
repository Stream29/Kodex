package io.github.stream29.kodex.cli.settings

import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import io.github.stream29.kodex.app.settings.contract.GlobalSettingsState
import io.github.stream29.kodex.app.settings.contract.SettingsAccountUsageState
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationState
import io.github.stream29.kodex.cli.components.rememberTuiDropdownState
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.OpenAiSubscriptionPlan
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlobalSettingsGroupingTest {
    @Test
    fun authenticationDetailsRemainTogetherBeforeTitleGeneration() = runTest {
        runMosaicTest {
            val titleModel = OpenAiModelId("title-model")
            val snapshot = setContentAndSnapshot {
                Column(Modifier.width(80)) {
                    GlobalAuthenticationAndTitleSettingsContent(
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
                        authentication = SettingsAuthenticationState.Authenticated(
                            planType = OpenAiSubscriptionPlan.Pro,
                            email = "person@example.com",
                        ),
                        accountUsage = SettingsAccountUsageState.Unavailable,
                        authenticationDropdown = rememberTuiDropdownState(),
                        modelDropdown = rememberTuiDropdownState(),
                        reasoningDropdown = rememberTuiDropdownState(),
                        onOpenLogin = {},
                        onRefreshUsage = {},
                        onUseReset = {},
                        onUpdateSessionTitleEnabled = {},
                    )
                }
            }

            val authentication = snapshot.indexOf("Authentication [Codex]")
            val account = snapshot.indexOf("OpenAI account")
            val usage = snapshot.indexOf("Codex usage")
            val titleGeneration = snapshot.indexOf("[x] Automatic session title")
            assertTrue(authentication >= 0, snapshot)
            assertTrue(authentication < account, snapshot)
            assertTrue(account < usage, snapshot)
            assertTrue(usage < titleGeneration, snapshot)
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
