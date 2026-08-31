package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.app.application.contract.ApplicationPopupState
import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import kotlinx.coroutines.coroutineScope
import kotlin.test.assertEquals
import kotlin.test.assertIs

val sessionTreeCliSessionDeletionTest by testSuite {
    test("deleting an open session removes persistent data, tab, and popup") {
        coroutineScope {
            val fixture = applicationFixture()
            val sessionIndex = fixture.repository.create()
            fixture.repository.open(sessionIndex).runtime.modify { storage ->
                storage.initialize(
                    KodexAgentSettings(
                        model = OpenAiModelId("test-model"),
                        threadName = "Delete me",
                    ),
                )
            }
            try {
                fixture.viewModel.openSession(sessionIndex)
                val popup = fixture.viewModel.openDeleteSessionPopup(sessionIndex)

                assertEquals(true, popup.viewModel.delete())

                assertEquals(emptyList(), fixture.repository.list())
                assertIs<ApplicationPopupState.Closed>(fixture.viewModel.popup.value)
                assertEquals(
                    emptyList(),
                    fixture.viewModel.navigation.value.tabs
                        .filterNot { tab -> tab is NewSessionViewModel },
                )
            } finally {
                fixture.close()
            }
        }
    }
}
