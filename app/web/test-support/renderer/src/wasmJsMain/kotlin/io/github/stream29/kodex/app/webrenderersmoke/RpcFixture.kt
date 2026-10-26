package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import io.github.stream29.kodex.app.ui.compose.ComposeApplicationScreen
import io.github.stream29.kodex.app.ui.UiAction
import io.github.stream29.kodex.app.ui.UiActions
import io.github.stream29.kodex.app.ui.UiColumn
import io.github.stream29.kodex.app.ui.UiText
import io.github.stream29.kodex.cli.agent.ComposerContent
import io.github.stream29.kodex.app.agent.contract.ComposerSubmissionState
import io.github.stream29.kodex.cli.app.ApplicationPageContent
import io.github.stream29.kodex.cli.app.KodexApplication
import io.github.stream29.kodex.cli.app.NewSessionDraft
import io.github.stream29.kodex.cli.app.withFrontendApplication
import kotlinx.coroutines.*

@Composable
internal fun RpcFixture(token: String, onBack: () -> Unit) {
    var fixture by remember { mutableStateOf<KodexApplication?>(null) }
    var connection by remember { mutableStateOf<FixtureConnection?>(null) }
    var status by remember { mutableStateOf("Connecting to isolated Native backend…") }
    var fullRoot by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(token) {
        if (token.length < 32) {
            status = "A runtime fixture token in the URL fragment is required. No RPC was opened."
            return@LaunchedEffect
        }
        try {
            withFixtureConnection(token) { cwd, connected ->
                connection = connected
                val host = FixtureHost(cwd) { status = "Frontend diagnostic: $it" }
                withFrontendApplication(connected, cwd, host, host.rpcEnvironment) { app ->
                    try {
                        fixture = app
                        status = "Connected — real DTOs, Native temporary Home, offline model"
                        awaitCancellation()
                    } finally { fixture = null }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            status = "Fixture failed: ${failure.message}"
        } finally { connection = null }
    }
    val graphical = fixture
    if (fullRoot && graphical != null) {
        Column(Modifier.fillMaxSize()) {
            UiText("Isolated full graphical root — not product authentication")
            UiText(status, maximumLines = 2)
            GraphicalInputDiagnostic(graphical)
            UiActions {
                UiAction("Return to slice fixture", { fullRoot = false })
                UiAction("Disconnect graphical fixture", onBack)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                ComposeApplicationScreen(
                    graphical.viewModel, graphical.newLineKey, graphical.sidebarSettings,
                    graphical.reportUnhandledError, openExternalUrl = { false },
                )
            }
        }
        return
    }
    UiColumn {
        UiText("Real RPC fixture — not the product Web entry")
        UiText(status)
        connection?.let { connected ->
            val state by connected.state.collectAsState()
            val generation by connected.generation.collectAsState()
            val discarded by connected.discardedReplies.collectAsState()
            val attempts by connected.writeAttempts.collectAsState()
            UiText("Fixture connection: $state; generation=$generation; discarded replies=$discarded")
            UiText("Fixture write attempts: appends=${attempts.appends}; resumes=${attempts.resumes}")
            UiActions {
                UiAction("Drop fixture transport", onClick = { scope.launch { connected.dropTransport() } })
                UiAction("Discard next accepted append reply", onClick = { connected.discardNextAppendReply = true })
            }
        }
        fixture?.let { app ->
            UiAction("Open full graphical root", { fullRoot = true })
            val navigation by app.viewModel.navigation.collectAsState()
            val newLineKey by app.newLineKey.collectAsState()
            UiText("Open fixture tabs: ${navigation.tabs.size}")
            for (tab in navigation.tabs) key(tab) {
                UiAction("Select retained Session ${tab.sessionIndex}", onClick = {
                    scope.launch { app.viewModel.selectTab(tab) }
                })
            }
            ApplicationPageContent(
                app.viewModel, navigation, app.reportUnhandledError,
                newSession = { draft, submit ->
                    val state by draft.composer.state.collectAsState()
                    UiText("New Session singleton")
                    NewSessionDraft(draft.composer, newLineKey, submit)
                    UiText("Singleton projection: cursor=${state.cursorOffset}; text=${state.text}")
                },
                persistedSession = { selected ->
                    val name by selected.name.collectAsState()
                    val agent by selected.rootAgent.collectAsState()
                    val lifecycle by selected.lifecycle.collectAsState()
                    UiText("Persisted fixture Session: $name")
                    UiText("Persisted lifecycle: $lifecycle")
                    agent?.let {
                        val composer by it.composer.state.collectAsState()
                        UiText("Persisted Composer: running=${composer.running}; " +
                            "submitting=${composer.submission is ComposerSubmissionState.Submitting}; mode=${composer.mode}")
                        key(it.history) { FixtureHistoryList(it.history) }
                        ComposerContent(it.composer, newLineKey)
                    }
                    UiAction("Return to singleton draft", onClick = {
                        scope.launch { app.viewModel.selectNewSession() }
                    })
                },
            )
        }
        UiAction("Disconnect fixture and return", onClick = onBack)
    }
}

/** Reads original children only; no diagnostic mirror or extra mutation/connection authority. */
@Composable
private fun GraphicalInputDiagnostic(app: KodexApplication) {
    val navigation = app.viewModel.navigation.collectAsState().value
    val selected = navigation.selectedSession
    if (selected == null) {
        val state = app.viewModel.newSession.composer.state.collectAsState().value
        UiText("Graphical projection: singleton; cursor=${state.cursorOffset}; text=${state.text}", maximumLines = 2)
    } else {
        val agent = selected.rootAgent.collectAsState().value
        val state = agent?.composer?.state?.collectAsState()?.value
        UiText(
            "Graphical projection: session=${selected.sessionIndex}; running=${state?.running}; " +
                "submitting=${state?.submission is ComposerSubmissionState.Submitting}; " +
                "cursor=${state?.cursorOffset}; text=${state?.text}",
            maximumLines = 2,
        )
    }
}
