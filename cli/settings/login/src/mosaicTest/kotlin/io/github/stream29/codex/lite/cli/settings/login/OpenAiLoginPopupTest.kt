package io.github.stream29.codex.lite.cli.settings.login

import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Box
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.cli.auth.InMemoryCodexAuthStore
import io.github.stream29.codex.lite.cli.components.TuiPopupHost
import io.github.stream29.codex.lite.openai.OpenAiSubscriptionAuthState
import kotlin.test.assertTrue

val openAiLoginPopupTest by testSuite {
    test("renders a browser sign-in popup") {
        val viewModel = OpenAiLoginViewModel(
            InMemoryCodexAuthStore(
                OpenAiSubscriptionAuthState(accessToken = "test-access-token"),
            ),
        )
        try {
            runMosaicTest {
                setContentAndSnapshot {
                    Box {
                        TuiPopupHost(modifier = Modifier.width(80).height(24)) {
                            OpenAiLoginPopup(
                                viewModel = viewModel,
                                onDismissRequest = {},
                            )
                        }
                    }
                }

                val snapshot = awaitSnapshot()
                assertTrue("Sign in to OpenAI" in snapshot, snapshot)
                assertTrue("Open browser" in snapshot, snapshot)
            }
        } finally {
            viewModel.close()
        }
    }
}
