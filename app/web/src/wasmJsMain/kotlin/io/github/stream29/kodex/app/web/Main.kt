@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.stream29.kodex.app.web

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import io.github.stream29.kodex.app.ui.*
import io.github.stream29.kodex.app.ui.compose.ComposeApplicationScreen
import io.github.stream29.kodex.backend.LocalFrontendHostJson
import io.github.stream29.kodex.backend.LocalFrontendPairingOffer
import io.github.stream29.kodex.backend.canonicalLocalBrowserOrigin
import io.github.stream29.kodex.cli.app.KodexApplication
import io.github.stream29.kodex.cli.app.withFrontendApplication
import io.github.stream29.kodex.rpc.browser.FrontendHostConnection
import io.github.stream29.kodex.rpc.browser.withLocalFrontendClient
import io.github.stream29.kodex.rpc.browser.withPairedFrontendClient
import io.github.stream29.kodex.rpc.local.FrontendRpcConnection
import kotlinx.coroutines.*
import kotlinx.io.files.Path
import kotlin.js.Promise
import kotlin.js.JsString

public actual fun main(args: Array<String>) {
    ComposeViewport { WithBrowserComponentRenderers { UiTheme { BrowserApplication() } } }
}

@Composable
private fun BrowserApplication() {
    val launchPath = remember { currentPath().takeIf { it.matches(Regex("/web/[0-9a-f]{64}/")) } }
    val launchTicket = remember { arrayOf(takeLaunchTicket()) }
    var automatic by remember { mutableStateOf(launchPath != null) }
    var pairing by remember { mutableStateOf<LocalFrontendPairingOffer?>(null) }
    var activeHost by remember { mutableStateOf<BrowserHost?>(null) }
    var application by remember { mutableStateOf<KodexApplication?>(null) }
    var connection by remember { mutableStateOf<FrontendRpcConnection?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var ownerActive by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val origin = remember { runCatching { canonicalLocalBrowserOrigin(currentOrigin()) }.getOrNull() }

    val selected = automatic || pairing != null
    LaunchedEffect(pairing, automatic) {
        if (!selected) return@LaunchedEffect
        ownerActive = true
        try {
            val connectedBlock: suspend CoroutineScope.(String, FrontendRpcConnection, FrontendHostConnection) -> Unit = { directory, connected, frontendServer ->
                connection = connected
                val path = Path(directory)
                val host = BrowserHost(path, frontendServer)
                withFrontendApplication(connected, path, host) { app ->
                    try { activeHost = host; application = app; awaitCancellation() }
                    finally { application = null; activeHost = null }
                }
            }
            if (automatic) {
                val ticket = launchTicket[0].also { launchTicket[0] = null }
                withLocalFrontendClient(currentOrigin(), requireNotNull(launchPath), ticket, connectedBlock)
            } else {
                withPairedFrontendClient(requireNotNull(pairing), connectedBlock)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Throwable) {
            message = if (automatic) "Could not connect to this local session. Run kodex web or kodex app again."
                else "Could not pair. Run kodex host for this exact Origin and select its new scoped pairing record within 60 seconds."
            pairing = null
            automatic = false
        } finally { connection = null; application = null; activeHost = null; ownerActive = false }
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
            connection?.takeIf { selected }?.let { connected ->
                val state by connected.state.collectAsState()
                UiText("Backend: $state", UiTextRole.Supporting)
            }
            if (selected) UiAction("Disconnect", { pairing = null; automatic = false; message = null })
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
        val app = application.takeIf { selected }
        val host = activeHost
        if (app != null && host != null) {
            val readiness by host.frontendServer.capabilities.collectAsState()
            val backendState by app.connectionState.collectAsState()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                ComposeApplicationScreen(
                    app.viewModel, app.newLineKey, app.sidebarSettings, app.reportUnhandledError,
                    openExternalUrl = host::openAuthorization,
                    capabilities = readiness.settingsCapabilities(backendState),
                )
            }
        } else if (selected || ownerActive) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)) {
                UiText(if (!selected) "Disconnecting…" else "Connecting to the selected local backend…")
            }
        } else if (launchPath != null) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)
                    .semantics { paneTitle = "Local Kodex session disconnected" },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                UiText("Local Kodex session disconnected", UiTextRole.Title)
                UiText("Run kodex web or kodex app to open a new session. Reload can reconnect while this session is valid.")
                UiText("The CLI hosts this page; Wasm connects directly to the backend with a scoped credential. A tab-scoped frontend capability enables reload while the host and admission remain alive. Closing the frontend does not stop backend work.", UiTextRole.Supporting)
            }
        } else {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)
                    .semantics { paneTitle = "Connect to a local backend" },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                UiText("Connect to a local backend", UiTextRole.Title)
                if (origin == null) {
                    UiText("Serve this package from an HTTP loopback origin (127.0.0.1 or localhost).", UiTextRole.Error)
                } else {
                    UiText("Start the frontend server with: kodex host --origin $origin")
                    UiText("Select the scoped pairing record printed by the frontend server within 60 seconds. Keep the frontend server running.")
                    UiText("Trust this Web package and the local OS/listener. This mode is not for remote or untrusted shared hosts.", UiTextRole.Supporting)
                    UiAction("Choose scoped pairing record", enabled = !choosing, onClick = {
                        scope.launch {
                            choosing = true
                            try {
                                val text = choosePairingRecord().await().toString()
                                if (text.isNotEmpty()) {
                                    pairing = LocalFrontendHostJson.decodeFromString(LocalFrontendPairingOffer.serializer(), text)
                                    message = null
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Throwable) { message = "Could not read a valid scoped pairing record. Do not select the backend management record." }
                            finally { choosing = false }
                        }
                    })
                    UiText("Credentials stay in memory, never in URLs or browser storage. Disconnect stops frontend renewal, not backend work.", UiTextRole.Supporting)
                    UiText("Directory and Context paths use the selected backend environment. OAuth callbacks and frontend error Hooks are handled by the backend.", UiTextRole.Supporting)
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

@JsFun("""() => new Promise((resolve, reject) => {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = '.json,application/json';
    input.style.display = 'none';
    input.oncancel = () => { input.remove(); resolve(''); };
    input.onchange = async () => {
        try {
            const file = input.files[0];
            if (!file) { resolve(''); return; }
            if (file.size > 65536) throw new Error('Pairing record is too large');
            resolve(await file.text());
        } catch { reject(new Error('Could not read scoped pairing record')); }
        finally { input.remove(); }
    };
    document.body.appendChild(input);
    input.click();
})""")
private external fun choosePairingRecord(): Promise<JsString>
