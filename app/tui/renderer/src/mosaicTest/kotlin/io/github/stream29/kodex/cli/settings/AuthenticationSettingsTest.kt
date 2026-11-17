package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.authenticationsettings.AuthenticationSettingsState
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationState
import io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers
import io.github.stream29.kodex.cli.authenticationsettings.AuthenticationSettingsBody
import io.github.stream29.kodex.cli.components.TuiDropdownTrigger
import io.github.stream29.kodex.cli.components.rememberTuiDropdownState
import io.github.stream29.kodex.openai.OpenAiAuthState
import io.github.stream29.kodex.openai.OpenAiSubscriptionPlan
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val authenticationSettingsTest by testSuite {
    test("authenticatedAccountRendersSafeSummary") {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                WithMosaicComponentRenderers {
                    Column(Modifier.width(80)) {
                        RenderAuthenticationBody(
                            AuthenticationSettingsState(
                                KodexAuthSource.Kodex,
                                SettingsAuthenticationState.Authenticated(
                                    accountId = "account-id",
                                    planType = OpenAiSubscriptionPlan.Pro,
                                    email = "person@example.com",
                                ),
                            ),
                        )
                    }
                }
            }

            assertTrue("Signed in as person@example.com" in snapshot, snapshot)
            assertTrue("Plan: pro" in snapshot, snapshot)
            assertFalse("account-id" in snapshot, snapshot)
            assertTrue("[Sign in again]" in snapshot, snapshot)
            assertFalse("[Reload]" in snapshot, snapshot)
            assertTrue("[Log out]" in snapshot, snapshot)
        }
    }

    test("unavailableAuthenticationRendersTypedReason") {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                WithMosaicComponentRenderers {
                    Column(Modifier.width(80)) {
                        RenderAuthenticationBody(
                            AuthenticationSettingsState(
                                KodexAuthSource.Kodex,
                                SettingsAuthenticationState.Unavailable(
                                    OpenAiAuthState.Unavailable.CredentialsNotFound,
                                ),
                            ),
                        )
                    }
                }
            }

            assertTrue("Authentication unavailable" in snapshot, snapshot)
            assertTrue("No credentials were found" in snapshot, snapshot)
            assertTrue("[Sign in]" in snapshot, snapshot)
            assertFalse("[Reload]" in snapshot, snapshot)
            assertFalse("[Log out]" in snapshot, snapshot)
        }
    }

    test("codexSourceUsesBackendLifecycleWithoutReload") {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                WithMosaicComponentRenderers {
                    Column(Modifier.width(80)) {
                        RenderAuthenticationBody(
                            AuthenticationSettingsState(
                                KodexAuthSource.Codex,
                                SettingsAuthenticationState.Authenticated(
                                    planType = OpenAiSubscriptionPlan.Pro,
                                    email = "person@example.com",
                                ),
                            ),
                        )
                    }
                }
            }

            assertTrue("Maintained by the backend" in snapshot, snapshot)
            assertFalse("[Reload]" in snapshot, snapshot)
            assertTrue("[Sign in again]" in snapshot, snapshot)
            assertTrue("[Log out]" in snapshot, snapshot)
        }
    }
}

@Composable
private fun RenderAuthenticationBody(state: AuthenticationSettingsState) {
    val dropdown = rememberTuiDropdownState()
    AuthenticationSettingsBody(
        state = state,
        onOpenLogin = {},
        onRequestLogout = {},
        onDismissFailure = {},
    ) { enabled ->
        Row {
            Text("Authentication: ")
            TuiDropdownTrigger(dropdown, state.selectedSource.name, enabled = enabled, autoFocus = true)
        }
    }
}
