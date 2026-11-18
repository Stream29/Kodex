@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.stream29.kodex.app.web

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import io.github.stream29.kodex.app.ui.*
import io.github.stream29.kodex.app.ui.compose.ComposeApplicationScreen
import io.github.stream29.kodex.cli.app.KodexApplication
import io.github.stream29.kodex.cli.app.withFrontendApplication
import io.github.stream29.kodex.rpc.browser.withLocalFrontendClient
import io.github.stream29.kodex.rpc.local.FrontendRpcConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.io.files.Path

public actual fun main(args: Array<String>) {
    ComposeViewport { WithBrowserComponentRenderers { UiTheme { BrowserApplication() } } }
}

@Composable
private fun BrowserApplication() {
    val launchPath = remember { currentPath().takeIf { it.matches(Regex("/web/[0-9a-f]{64}/")) } }
    val launchTicket = remember { arrayOf(takeLaunchTicket()) }
    var connectedPage by remember { mutableStateOf(launchPath != null) }
    var connecting by remember { mutableStateOf(false) }
    var application by remember { mutableStateOf<KodexApplication?>(null) }
    var connection by remember { mutableStateOf<FrontendRpcConnection?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(connectedPage) {
        if (!connectedPage) return@LaunchedEffect
        connecting = true
        try {
            val ticket = launchTicket[0].also { launchTicket[0] = null }
            withLocalFrontendClient(currentOrigin(), requireNotNull(launchPath), ticket) { directory, connected ->
                connection = connected
                withFrontendApplication(connected, Path(directory)) { app ->
                    application = app
                    awaitCancellation()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            message = "Could not connect to this local session. Run kodex web or kodex app again."
            connectedPage = false
        } finally {
            connection = null
            application = null
            connecting = false
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewportHeight = maxHeight
        Column(Modifier.fillMaxSize()) {
            FlowRow(
                Modifier.fillMaxWidth().heightIn(max = viewportHeight / 3)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Kodex", style = MaterialTheme.typography.titleMedium)
                connection?.takeIf { connectedPage }?.let { connected ->
                    val state by connected.state.collectAsState()
                    UiText("Backend: $state", UiTextRole.Supporting)
                }
                if (connectedPage) UiAction("Disconnect", { connectedPage = false; message = null })
            }
            message?.let {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = viewportHeight / 3)
                        .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    UiText(it, UiTextRole.Error)
                    UiAction("Dismiss message", { message = null })
                }
            }
            val app = application.takeIf { connectedPage }
            when {
                app != null -> {
                    val backendState by app.connectionState.collectAsState()
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        ComposeApplicationScreen(
                            app.viewModel, app.newLineKey, app.sidebarSettings, app.reportUnhandledError,
                            // OAuth already belongs to the backend; the dialog presents the exact URL.
                            openExternalUrl = { false },
                            capabilities = backendState.settingsCapabilities(),
                        )
                    }
                }
                connectedPage || connecting -> {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)) {
                        UiText(if (connectedPage) "Connecting to the selected local backend…" else "Disconnecting…")
                    }
                }
                else -> {
                    val title = if (launchPath == null) "Open Kodex from the CLI" else "Local Kodex session disconnected"
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)
                            .semantics { paneTitle = title },
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        UiText(title, UiTextRole.Title)
                        UiText("Run kodex web or kodex app to open a local session.")
                        if (launchPath != null) {
                            UiText("Reload can reconnect while the host and its grant remain active. Disconnecting this page does not stop backend work.", UiTextRole.Supporting)
                        }
                    }
                }
            }
        }
    }
}

@JsFun("() => location.origin")
private external fun currentOrigin(): String

@JsFun("() => location.pathname")
private external fun currentPath(): String

@JsFun("() => window.kodexTakeLaunchTicket?.() ?? null")
private external fun takeLaunchTicket(): String?
