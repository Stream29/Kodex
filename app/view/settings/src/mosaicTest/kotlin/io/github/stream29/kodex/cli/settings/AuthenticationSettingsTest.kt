package io.github.stream29.kodex.cli.settings

import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationState
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
                        authState = SettingsAuthenticationState.Authenticated(
                            accountId = "account-id",
                            planType = OpenAiSubscriptionPlan.Pro,
                            email = "person@example.com",
                        ),
                        onOpenLogin = {},
                    )
                }
            }

            assertTrue("Signed in as person@example.com" in snapshot, snapshot)
            assertTrue("Plan: pro" in snapshot, snapshot)
            assertFalse("account-id" in snapshot, snapshot)
            assertFalse("[Sign in]" in snapshot, snapshot)
        }
    }

    @Test
    fun unavailableAuthenticationRendersTypedReason() = runTest {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Column(Modifier.width(80)) {
                    AuthenticationSettingsContent(
                        authState = SettingsAuthenticationState.Unavailable(
                            OpenAiAuthState.Unavailable.CredentialsNotFound,
                        ),
                        onOpenLogin = {},
                    )
                }
            }

            assertTrue("Authentication unavailable" in snapshot, snapshot)
            assertTrue(
                "No credentials were found in the selected authentication source." in snapshot,
                snapshot,
            )
            assertTrue("[Sign in]" in snapshot, snapshot)
        }
    }
}
