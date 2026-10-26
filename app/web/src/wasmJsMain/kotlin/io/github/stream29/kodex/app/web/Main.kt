@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.stream29.kodex.app.web

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import io.github.stream29.kodex.app.ui.*
import io.github.stream29.kodex.app.ui.compose.ComposeApplicationScreen
import io.github.stream29.kodex.backend.LocalBrowserEndpoint
import io.github.stream29.kodex.backend.canonicalLocalBrowserOrigin
import io.github.stream29.kodex.cli.app.KodexApplication
import io.github.stream29.kodex.cli.app.withFrontendApplication
import io.github.stream29.kodex.cli.settings.SettingsHostCapabilities
import io.github.stream29.kodex.rpc.browser.withLocalBrowserClient
import io.github.stream29.kodex.rpc.browser.withLocalWebClient
import io.github.stream29.kodex.rpc.local.FrontendRpcConnection
import kotlinx.coroutines.*
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
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
    var endpoint by remember { mutableStateOf<LocalBrowserEndpoint?>(null) }
    var application by remember { mutableStateOf<KodexApplication?>(null) }
    var connection by remember { mutableStateOf<FrontendRpcConnection?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var cwd by remember { mutableStateOf("") }
    var choosing by remember { mutableStateOf(false) }
    var ownerActive by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val origin = remember { runCatching { canonicalLocalBrowserOrigin(currentOrigin()) }.getOrNull() }

    val selected = automatic || endpoint != null
    LaunchedEffect(endpoint, automatic) {
        if (!selected) return@LaunchedEffect
        ownerActive = true
        try {
            val connectedBlock: suspend CoroutineScope.(String, FrontendRpcConnection) -> Unit = { directory, connected ->
                connection = connected
                val path = Path(directory)
                val host = BrowserHost(path) {
                    if (it != null) message = "A frontend operation failed. Check current state before retrying."
                }
                withFrontendApplication(connected, path, host, host.rpcEnvironment) { app ->
                    try { application = app; awaitCancellation() }
                    finally { application = null }
                }
            }
            if (automatic) {
                val ticket = launchTicket[0].also { launchTicket[0] = null }
                withLocalWebClient(currentOrigin(), requireNotNull(launchPath), ticket, connectedBlock)
            } else {
                withLocalBrowserClient(requireNotNull(endpoint), cwd.takeUnless { it.isBlank() }, connectedBlock)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Throwable) {
            message = if (automatic) "Could not connect to this local session. Run kodex web or kodex app again."
                else "Could not connect. Check the current private connection record, backend readiness and --web-origin."
            endpoint = null
            automatic = false
        } finally { connection = null; application = null; ownerActive = false }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text("Kodex", style = MaterialTheme.typography.titleLarge)
            connection?.takeIf { selected }?.let { connected ->
                val state by connected.state.collectAsState()
                UiText("Backend: $state", UiTextRole.Supporting)
            }
            Spacer(Modifier.weight(1f))
            if (selected) UiAction("Disconnect", { endpoint = null; automatic = false; message = null })
        }
        message?.let {
            UiText(it, UiTextRole.Error, 2)
            UiAction("Dismiss message", { message = null })
        }
        val app = application.takeIf { selected }
        if (app != null) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                ComposeApplicationScreen(
                    app.viewModel, app.newLineKey, app.sidebarSettings, app.reportUnhandledError,
                    openExternalUrl = { openExternal(it) },
                    capabilities = SettingsHostCapabilities(false, false, false),
                )
            }
        } else if (selected || ownerActive) {
            UiText(if (!selected) "Disconnecting…" else "Connecting to the selected local backend…")
        } else if (launchPath != null) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                UiText("Local Kodex session disconnected", UiTextRole.Title)
                UiText("Run kodex web or kodex app to open a new session. Reload can reconnect while this session is valid.")
                UiText("The CLI hosts this page; Wasm connects directly to the backend with a scoped credential. A tab-scoped frontend capability enables reload while the host and admission remain alive. Closing the frontend does not stop backend work.", UiTextRole.Supporting)
            }
        } else {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                UiText("Connect to a local backend", UiTextRole.Title)
                if (origin == null) {
                    UiText("Serve this package from an HTTP loopback origin (127.0.0.1 or localhost).", UiTextRole.Error)
                } else {
                    UiText("Start the backend with: kodex backend start --web-origin $origin")
                    UiText("Select its private <Kodex Home>/.locks/backend/connection.json explicitly. It grants access to this backend.")
                    UiText("Trust this Web package and the local OS/listener. This mode is not for remote or untrusted shared hosts.", UiTextRole.Supporting)
                    UiTextInput(cwd, { cwd = it }, label = "Initial backend directory (blank: backend Home)")
                    UiAction("Choose private connection record", enabled = !choosing, onClick = {
                        scope.launch {
                            choosing = true
                            try {
                                val text = chooseConnectionRecord().await().toString()
                                if (text.isNotEmpty()) {
                                    endpoint = Json { ignoreUnknownKeys = true }.decodeFromString(LocalBrowserEndpoint.serializer(), text)
                                    message = null
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Throwable) { message = "Could not read a valid private connection record." }
                            finally { choosing = false }
                        }
                    })
                    UiText("Credentials stay in memory, never in URLs or browser storage. Disconnect stops frontend renewal, not backend work.", UiTextRole.Supporting)
                    UiText("Directory browsing, context path editing, provider OAuth and frontend shell hooks are unavailable. Use the Native client for these operations.", UiTextRole.Supporting)
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

@JsFun("""(value) => {
    try {
        const url = new URL(value);
        if (url.protocol !== 'http:' && url.protocol !== 'https:') return false;
        const tab = window.open('about:blank', '_blank');
        if (!tab) return false;
        tab.opener = null;
        tab.location.replace(url.href);
        return true;
    } catch { return false; }
}""")
private external fun openExternal(value: String): Boolean

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
            if (file.size > 16384) throw new Error('Connection record is too large');
            resolve(await file.text());
        } catch { reject(new Error('Could not read private connection record')); }
        finally { input.remove(); }
    };
    document.body.appendChild(input);
    input.click();
})""")
private external fun chooseConnectionRecord(): Promise<JsString>
