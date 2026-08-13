package io.github.stream29.kodex.cli.settings

import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Box
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.settings.createOpenAiLoginViewModel
import io.github.stream29.kodex.cli.auth.InMemoryKodexAuthStore
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.openai.OpenAiSubscriptionAuthState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.assertTrue

val openAiLoginPopupTest by testSuite {
    test("renders a browser sign-in popup") {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val viewModel = createOpenAiLoginViewModel(
            authStore = InMemoryKodexAuthStore(
                OpenAiSubscriptionAuthState(accessToken = "test-access-token"),
            ),
            ownerScope = scope,
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
            scope.cancel()
        }
    }
}
