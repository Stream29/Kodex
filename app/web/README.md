# Kodex Web — local browser entry

- This Compose Wasm frontend connects directly to a local Kodex backend, either from the single-file CLI or an independent Web distribution.
- It consumes the shared Application, ViewModels and page composition. Static hosting does not proxy business RPC or own backend work.
- This is a same-machine entry, not cloud authentication or a hosted remote service.
- The source includes the scoped frontend server integration below. Source, installed executable and published release are separate checkpoints; earlier visual/browser results alone do not establish capability parity.

## Single-file local entry

These commands belong to the current source build; source delivery does not update an installed binary or publish a release.

```sh
cd /path/to/project
kodex web
# Or open a system WebView window:
kodex app
```

- Upgrading across the business-protocol checkpoint requires a compatible backend and newly started frontend servers. Finish active work, close the old `web`/`app` servers, explicitly stop the old backend with `kodex backend stop`, wait for `kodex backend status` to report `not_running`, then launch the new entry. Refreshing a tab still served by an old server does not replace its embedded website. The installer does not stop user processes.

- Both reuse the existing compatible backend or start it independently. The caller's absolute directory is validated and canonicalized by the backend. No static-server command, connection-file picker, Node or JDK is needed at runtime.
- The `web` process owns a separate loopback frontend server for static resources and connection bootstrap, and stays in the foreground until Ctrl+C/SIGTERM. Opening the default browser is only a launcher handoff, not proof that the page loaded. Closing the tab does not terminate this server.
- `app` owns the same listener plus one Kauri native window. Window close or a cooperative process signal releases these frontend resources and revokes browser admission; it does not stop the backend or accepted work. The backend never owns the website or opens the window.
- The executable embeds the same production website described below, including fonts and notices. It serves resources directly from bounded readers, without extracting an adjacent website directory.
- `app` requires a display and a browser engine. Linux defaults to a separate Chrome application window; the system WebView can be selected explicitly. macOS and Windows default to the system WebView. Browser engines are not embedded; Linux loads GTK/WebKit only for the system WebView.
- Native management authentication registers a random browser-only credential bound to the frontend's exact Origin, backend instance and canonical initial directory. Each authenticated bootstrap acquires a separate page owner, browser credential and frontend server credential. Neither page credential authorizes management; credentials never appear in HTTP paths/query, static resources or product diagnostics.
- The URL fragment contains only the separate frontend capability. HTML removes it before Wasm starts and retains it in tab-scoped `sessionStorage` for refresh. The scoped backend credential stays in page memory. No cookie is set or used; this is not a promise of memory-only frontend authorization: sessionStorage can survive a browser's session restore. Denied storage permits the initial launch but cannot support refresh.
- Wasm connects directly to the backend's `/browser/rpc` and `/heartbeat`, not through the frontend server. The backend provides business services and browser admission. Management routes reject browser Origins; heartbeat CORS and the WebSocket hello enforce exact Origin/Host, instance and versions.
- Browser admission and frontend server owners have separate finite leases. Static GETs, Origin probes, socket existence and an idle frontend server never renew backend lifetime. A bootstrap creates a new scoped owner rather than reviving an expired one. Connected pages renew normally within 20 seconds; suspended/background pages cannot promise continued admission. Explicit reload may acquire a fresh owner while the same server, launch capability and backend remain valid; it does not restore a transient login or replay errors/writes.
- Cooperative host shutdown attempts to revoke admission and its transports. A failed revoke or killed/crashed host is bounded by the lease instead. An ended/restarted backend requires a new command too. Old pages never discover or retarget a replacement. A business mismatch or missing `browser_grant_protocol` fails explicitly; the launcher does not stop or replace an older backend automatically.
- Initial directory binding is not a filesystem sandbox. This trusts the local OS/listener, as does the manual mode below.

Build the matching Native executable using the verified toolchain, for example `:app-cli:linkReleaseExecutableLinuxX64`. Its task graph builds the production website, packs it and links the target resource object. `kodex.nodeCommand` and `kodex.assetClang` select the build-time Node/Clang executables; these are not product runtime requirements.

## Build and distribute

Use the repository's verified JDK/Gradle Daemon and toolchain conventions:

```sh
./gradlew :app-web:webArchive :app-web:checkSpecApiModule
```

- Website: `app/web/build/dist/wasmJs/productionExecutable/`.
- Archive: `app/web/build/distributions/kodex-web-<application-version>.zip`.
- The archive contains `version.txt` and a `SHA256SUMS` manifest covering every website file. After extraction, `sha256sum -c SHA256SUMS` verifies completeness, not publisher identity.
- Serve the entire extracted tree, including packaged fonts/resources. Do not copy only the `.wasm` file.
- Serve `.wasm` as `application/wasm`, JavaScript as JavaScript, and preserve relative resource paths. HTML/version metadata should revalidate; deploy assets as one versioned directory, never mix releases. No service worker is installed. The independent manual-import mode keeps its private record only in page memory.
- Keep the static host bound to loopback. A simple static HTTP server is sufficient; no Node business backend is needed.

## Manual connection from an independent website

1. Serve the package at an HTTP origin such as `http://127.0.0.1:18795`.
2. Run the matching frontend server from the intended initial working directory:

   ```sh
   kodex web --origin http://127.0.0.1:18795
   ```

   This reuses or independently starts a compatible backend without granting the page management access. The exact static Origin is explicit: HTTP `127.0.0.1` or `localhost`, no path/query/fragment; the port is part of the binding.
3. Open the website and select the **scoped pairing record** whose path the frontend server prints. The owner-private file contains a single-use offer valid for 60 seconds, not a backend management password. Do not select `.locks/backend/connection.json`.
4. Keep the frontend server running. The backend validates/canonicalizes the captured launch directory; the website cannot widen the offer's Origin, instance or initial directory.

- No automatic Home/file discovery, credential URL, localStorage or IndexedDB persistence. After an offer is consumed or expires, manual reload requires a fresh frontend server invocation/offer; a lost pairing reply is not retried with that offer.
- A currently running backend is not replaced by the website or frontend server. An incompatible backend requires an operator-controlled restart, never an automatic stop.
- The native TUI and management commands keep their existing header-authenticated paths.
- Browser admission checks exact Origin/Host, backend instance, transport and business versions before registering the original RPC services. Manual pairing POST/preflight permits only the explicitly paired Origin and JSON content type; management routes remain unavailable to the browser.
- Renewal belongs to the connected frontend, independently of one WebSocket. Disconnect stops renewal, not accepted backend work. Streaming observations rebind after exact-instance recovery; one-shot writes are never replayed when their result is unknown.
- Browsers can throttle/suspend background tabs. A suspended page cannot guarantee renewal or prevent configured backend idle expiry. Restart or explicit rejection requires a fresh pairing, not retargeting old drafts.

## Trust and execution environments

- Trust the Web package/static Origin and current local OS/listener. Scoped pairing grants access to the selected backend and restricted frontend server connection operations. Loopback HTTP does not protect against a compromised local OS; this is not a cloud login/session protocol.
- Directory browsing and Context path evaluation use the backend's actual OS/Home and captured initial base, for both TUI and Wasm. They reuse the same picker/Context ViewModels; path queries do not grant filesystem authority or move Context files.
- OpenAI/MCP callback listeners, PKCE validation, exchange and credential persistence are backend-owned. The frontend can present the authorization URL; the frontend server does not execute OAuth callbacks or arbitrary URL-open or HTTP-forward requests.
- The existing trusted `BackendSettings`/CAS business contract includes embedded MCP credentials in its private frontend adapter baseline. This is not a secret-free Wasm boundary. Management credentials and OpenAI tokens must not enter the page; MCP secrets must not enter the frontend server pairing channel, rendered state or diagnostics. Redesigning the older settings contract requires a separate protocol decision.
- Frontend errors are reported over a restricted RPC; the backend runs the configured Hook in backend Home. The frontend does not send a command, working directory or environment to execute. Backend business Hooks remain a single backend consumer.
- Directory and Context capability indicators reflect frontend server readiness; provider login and frontend error Hook indicators reflect the backend connection. No credentials, no selected Session, user cancellation and provider/filesystem failures remain legitimate business conditions.
- Owner close releases its connection resources; page crash is bounded by a 60-second owner lease, not instantaneous process exit. Neither frontend disposal nor pending-only login release cancels accepted backend exchange/persistence or accepted frontend error Hooks; explicit user Cancel retains its exact-attempt semantics.
- Initial directory validation is not a filesystem sandbox or backend authorization boundary. Model/tool execution and storage still belong to the backend.
- History prose and URLs are selectable plain text; images use safe references/placeholders rather than inline previews. This migration does not add a rich-media renderer.
- The [pinned Wasm UI binary](../../dependency-fix/compose-ui/README.md) repairs Compose popup-owner restoration, disabled AX mapping, stale click dispatch and explicit control role/state mapping. Normal builds resolve the immutable GitHub package, not a source composite; the official UI root and other variants remain unchanged. The original strict browser assertions remain enabled; this is not complete accessibility conformance or acceptance on untested platforms.

## Validation boundaries

- Tests below have different boundaries. Source preparation, compilation, offscreen rendering and real browser runs are separate results; none updates an installed executable.

- Early launch-ticket removal: `node --test app/web/scripts/test-local-bootstrap.mjs`, without a browser or backend.
- Single-file Linux entry: `node app/web/scripts/check-local-entry.mjs /path/to/kodex-cli.kexe /path/to/evidence`. This copies only the executable, creates a temporary Home, blocks runtime toolchain commands and owns a headless Chrome profile. Its private `xdg-open` adapter does not change the user's default browser configuration. The driver checks foreground-host lifetime, separate resource/business ports, no cookie, scoped admission, direct RPC, refresh and revocation without stopping the backend. A pass applies only to the executable actually supplied to that run.
- Protocol/admission and original Native paths: `:app-rpc-protocol:linuxX64Test`, `:rpc-local-server:linuxX64Test`, `:app-cli:linuxX64Test`.
- Connection routing/recovery: `:rpc-browser-client:wasmJsBrowserTest`, with a headless browser only as the batch test runtime.
- Shared pages/capability degradation: renderer test-support's raster/offscreen suite, not repeated full business-browser sessions.
- Framework compatibility: the independent producer's `binary-probe` build exercises raster owners and their DOM listener against staged Maven binaries, including pre-mirror dispatch and layout reuse. The publication workflow requires all four strict regressions and checks official-root/fork-leaf selection.
- Product integration: `app-web-test-support-backend`'s separate `browser` executable wraps production `runBackendHost` with a temporary Home and offline SDK. It does not replace the admission route or business graph, and is never included in this package.

For production capability checks, build the matching website and offline Native
fixture, then use the owning controller with NVM-managed Node and an explicitly
selected headless Chrome:

```sh
CHROME_BIN=/usr/bin/google-chrome \
  node app/web/scripts/check-capabilities.mjs \
  app/web/test-support/backend/build/bin/linuxX64/browserDebugExecutable/kodex-product-browser-fixture.kexe \
  app/web/build/dist/wasmJs/productionExecutable \
  build/wasm/package.json /path/to/evidence
```

- Keep static port 18795 and the provider-supported OpenAI callback ports free. A conflict blocks the check; never stop another listener to make it pass.
- The controller creates private OS/backend Homes, a fake external provider/model, frontend servers and separate Chrome profiles. It runs automatic and scoped-pairing modes sequentially, then cleans up its own resources. Never substitute a real Home or account.
- The controller may read its own management record for status/cleanup and isolation assertions; Wasm receives only scoped pairing/bootstrap credentials. `check-product.mjs` is its child driver, not an instruction to import `.locks/backend/connection.json`.
- Directory/Context operations, OpenAI/MCP login and settings use the production Wasm, shared ViewModels, backend graph and Native callback listener. A private `open`/`xdg-open` shim accepts only this fake provider's registered URL; it does not open the user's browser or change MIME defaults.
- Frontend error-Hook configuration and actual backend Stop execution are distinct checks. A Stop marker is not proof of a page-originated frontend error reaching its runner; keep that evidence separate from Native roundtrip and browser-adapter tests.
- The driver retains real streaming, cursor/draft, runtime menu, narrow Settings, rename/delete, spinner raster and disconnect checks. Enabled buttons alone are not functional acceptance.
- Recovery closes a real browser-owned WebSocket through a driver-only preloaded wrapper. It changes neither product assets nor backend routes; it is not proof of every offline/packet-loss timing. Chrome's offline emulation did not close the already-open WebSocket in the first run.
- Functional results/screenshots are recorded separately from the final strict disabled-state accessibility assertion. A functional success followed by nonzero exit is not a whole-script pass; raster semantics do not replace browser AX acceptance.
- Fixture stdin `pair` issues a fresh single-use offer; an empty line stops its servers/backend and removes its Home. `restart` is an in-process backend restart and is allowed only without frontend servers, not an OS-process restart check.
- `KODEX_BROWSER_FIXTURE_DELAY_MS` slows fake response parts (1–5000 ms, default 300). Such controls are test-support only, never product routes.
- `check-history-product.mjs` uses its own automatic frontend launch file and private History fixture. It checks actual raster/text geometry, pagination, streaming reading anchors and recovery; a matrix-only or discovery result is not a strict pass.
- The older `check-lifecycle.mjs` still uses pre-pairing import semantics. Its historical results are not current capability/credential-isolation evidence; migrate it before rerunning against this source.
- Headless Chrome can retain a hidden document with animation frames paused after unfreeze. Focus/visibility restoration in a diagnostic is not proof of normal background-tab renewal.
- To inspect that host behavior without Wasm, a backend or credentials, run `node app/web/scripts/check-lifecycle-host.mjs`. It prints visibility, focus, animation-frame and ordinary DOM-click observations; it is diagnostic, not product acceptance.
- Expected fetch rejection is contained in connection recovery; cancellation still releases the connection owner. Runtime exceptions remain a strict failure, not an expected result to filter out.

## Historical platform evidence — before frontend-host decoupling

- The results below apply to the preceding Daemon-hosted implementation. They are not acceptance of the new resource/bootstrap ownership or scoped admission. MD3, HTML editing and Kauri implementation are preserved by this refactor, but their integrated behavior still needs a matching rebuild and isolated execution.

- Historical Linux single-file CLI: copied executable in a temporary Home, no adjacent assets, automatic backend reuse/start, cookie-authenticated original RPC, reload and independent browser/backend exit. Runtime Node/Java commands were blocked by the test launcher. Real `xdg-open` was separately checked with an isolated MIME default and owned Sway Chrome, not the daily browser.
- Linux WebKitGTK 2.52.6 in embedded Sway: automatic full-root connection, native Fcitx Pinyin/commit and composition Enter protection, native clipboard roundtrip, settings focus return, window close and cooperative SIGTERM. The final window cleanup no longer emits the reproduced disconnected-signal criticals. This does not establish a minimum WebKitGTK version.
- Linux Chrome 156 headless: optimized product/root integration, exact-instance restart, independent renewal, frozen-page idle expiry and explicit recovery; 900×700 and 1280×1000 desktop geometry samples.
- Linux Chrome 155 in embedded Sway: real Fcitx Pinyin/commit and Enter guard, native clipboard roundtrip, root focus return and actual read-only History copy.
- These checks do not establish a minimum supported browser version, mobile support, Firefox/Safari coverage, macOS/Windows acceptance or complete accessibility conformance. Keep those targets explicit when expanding support.
