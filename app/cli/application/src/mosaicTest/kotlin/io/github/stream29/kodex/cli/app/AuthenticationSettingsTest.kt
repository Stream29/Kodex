package io.github.stream29.kodex.cli.app

import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import io.github.stream29.kodex.cli.auth.KodexAuthState
import io.github.stream29.kodex.openai.OpenAiSubscriptionAuthState
import io.github.stream29.kodex.openai.OpenAiSubscriptionPlan
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthenticationSettingsTest {
    @Test
    fun authenticatedAccountRendersEmailAndPlanWithoutCredentials() = runTest {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Column(Modifier.width(80)) {
                    AuthenticationSettingsContent(
                        authState = KodexAuthState.Authenticated(
                            OpenAiSubscriptionAuthState(
                                accessToken = "secret-access-token",
                                accountId = "account-id",
                                planType = OpenAiSubscriptionPlan.Pro,
                                email = "person@example.com",
                            ),
                        ),
                        onOpenLogin = {},
                    )
                }
            }

            assertTrue("Signed in as person@example.com" in snapshot, snapshot)
            assertTrue("Plan: pro" in snapshot, snapshot)
            assertFalse("secret-access-token" in snapshot, snapshot)
            assertFalse("account-id" in snapshot, snapshot)
        }
    }

    @Test
    fun authenticatedAccountFallsBackToAccountId() = runTest {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Column(Modifier.width(80)) {
                    AuthenticationSettingsContent(
                        authState = KodexAuthState.Authenticated(
                            OpenAiSubscriptionAuthState(
                                accessToken = "secret-access-token",
                                accountId = "account-id",
                            ),
                        ),
                        onOpenLogin = {},
                    )
                }
            }

            assertTrue("Signed in as account account-id" in snapshot, snapshot)
        }
    }

    @Test
    fun unavailableAuthenticationRendersItsReason() = runTest {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Column(Modifier.width(80)) {
                    AuthenticationSettingsContent(
                        authState = KodexAuthState.Unavailable("Selected credentials are missing."),
                        onOpenLogin = {},
                    )
                }
            }

            assertTrue("Authentication unavailable" in snapshot, snapshot)
            assertTrue("Selected credentials are missing." in snapshot, snapshot)
        }
    }
}
