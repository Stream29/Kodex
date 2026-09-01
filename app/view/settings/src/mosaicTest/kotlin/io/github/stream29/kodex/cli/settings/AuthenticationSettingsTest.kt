package io.github.stream29.kodex.cli.settings

import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationOperationState
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationState
import io.github.stream29.kodex.cli.settings.KodexAuthSource
import io.github.stream29.kodex.openai.OpenAiAuthState
import io.github.stream29.kodex.openai.OpenAiSubscriptionPlan
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthenticationSettingsTest {
    @Test
    fun authenticatedAccountRendersSafeSummary() = runTest {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Column(Modifier.width(80)) {
                    AuthenticationSettingsContent(
                        authSource = KodexAuthSource.Kodex,
                        authState = SettingsAuthenticationState.Authenticated(
                            accountId = "account-id",
                            planType = OpenAiSubscriptionPlan.Pro,
                            email = "person@example.com",
                        ),
                        operation = SettingsAuthenticationOperationState.Idle,
                        onOpenLogin = {},
                        onReload = {},
                        onRequestLogout = {},
                        onDismissOperationFailure = {},
                    )
                }
            }

            assertTrue("Signed in as person@example.com" in snapshot, snapshot)
            assertTrue("Plan: pro" in snapshot, snapshot)
            assertFalse("account-id" in snapshot, snapshot)
            assertTrue("[Sign in again]" in snapshot, snapshot)
            assertTrue("[Reload]" in snapshot, snapshot)
            assertTrue("[Log out]" in snapshot, snapshot)
        }
    }

    @Test
    fun unavailableAuthenticationRendersTypedReason() = runTest {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Column(Modifier.width(80)) {
                    AuthenticationSettingsContent(
                        authSource = KodexAuthSource.Kodex,
                        authState = SettingsAuthenticationState.Unavailable(
                            OpenAiAuthState.Unavailable.CredentialsNotFound,
                        ),
                        operation = SettingsAuthenticationOperationState.Idle,
                        onOpenLogin = {},
                        onReload = {},
                        onRequestLogout = {},
                        onDismissOperationFailure = {},
                    )
                }
            }

            assertTrue("Authentication unavailable" in snapshot, snapshot)
            assertTrue("No credentials were found" in snapshot, snapshot)
            assertTrue("[Sign in]" in snapshot, snapshot)
            assertTrue("[Reload]" in snapshot, snapshot)
            assertFalse("[Log out]" in snapshot, snapshot)
        }
    }

    @Test
    fun codexCredentialsRemainReadOnly() = runTest {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Column(Modifier.width(80)) {
                    AuthenticationSettingsContent(
                        authSource = KodexAuthSource.Codex,
                        authState = SettingsAuthenticationState.Authenticated(
                            planType = OpenAiSubscriptionPlan.Pro,
                            email = "person@example.com",
                        ),
                        operation = SettingsAuthenticationOperationState.Idle,
                        onOpenLogin = {},
                        onReload = {},
                        onRequestLogout = {},
                        onDismissOperationFailure = {},
                    )
                }
            }

            assertTrue("Managed by Codex CLI" in snapshot, snapshot)
            assertTrue("[Reload]" in snapshot, snapshot)
            assertFalse("[Sign in again]" in snapshot, snapshot)
            assertFalse("[Log out]" in snapshot, snapshot)
        }
    }
}
