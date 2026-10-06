package io.github.stream29.kodex.cli.settings

import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import io.github.stream29.kodex.app.authenticationsettings.AuthenticationSettingsState
import io.github.stream29.kodex.cli.authenticationsettings.AuthenticationSettingsContent
import io.github.stream29.kodex.cli.components.rememberTuiDropdownState
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationOperationState
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationState
import io.github.stream29.kodex.cli.settings.KodexAuthSource
import io.github.stream29.kodex.openai.OpenAiAuthState
import io.github.stream29.kodex.openai.OpenAiSubscriptionPlan
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val authenticationSettingsTest by testSuite {
    test("authenticatedAccountRendersSafeSummary") {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Column(Modifier.width(80)) {
                    AuthenticationSettingsContent(
                        state = AuthenticationSettingsState(KodexAuthSource.Kodex, SettingsAuthenticationState.Authenticated(
                            accountId = "account-id",
                            planType = OpenAiSubscriptionPlan.Pro,
                            email = "person@example.com",
                        )),
                        sourceDropdown = rememberTuiDropdownState(),
                        onOpenLogin = {},
                        onRequestLogout = {},
                        onDismissFailure = {},
                    )
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
                Column(Modifier.width(80)) {
                    AuthenticationSettingsContent(
                        state = AuthenticationSettingsState(KodexAuthSource.Kodex, SettingsAuthenticationState.Unavailable(
                            OpenAiAuthState.Unavailable.CredentialsNotFound,
                        )),
                        sourceDropdown = rememberTuiDropdownState(),
                        onOpenLogin = {},
                        onRequestLogout = {},
                        onDismissFailure = {},
                    )
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
                Column(Modifier.width(80)) {
                    AuthenticationSettingsContent(
                        state = AuthenticationSettingsState(KodexAuthSource.Codex, SettingsAuthenticationState.Authenticated(
                            planType = OpenAiSubscriptionPlan.Pro,
                            email = "person@example.com",
                        )),
                        sourceDropdown = rememberTuiDropdownState(),
                        onOpenLogin = {},
                        onRequestLogout = {},
                        onDismissFailure = {},
                    )
                }
            }

            assertTrue("Maintained by the backend" in snapshot, snapshot)
            assertFalse("[Reload]" in snapshot, snapshot)
            assertTrue("[Sign in again]" in snapshot, snapshot)
            assertTrue("[Log out]" in snapshot, snapshot)
        }
    }
}
