# Kodex Web — local browser entry

- This Compose Wasm frontend connects directly to a local Kodex backend, either from the single-file CLI or an independent Web distribution.
- It consumes the shared Application, ViewModels and page composition. Static hosting does not proxy business RPC or own backend work.
- This is a same-machine entry, not cloud authentication or a hosted remote service.

## Single-file local entry

These commands belong to the current source build; source delivery does not update an installed binary or publish a release.

```sh
cd /path/to/project
kodex web
# Or open a system WebView window:
kodex app
```

- Both reuse the existing compatible backend or start it independently. The caller's absolute directory is validated and canonicalized by the backend. No static-server command, connection-file picker, Node or JDK is needed at runtime.
- The `web` process owns a separate loopback static/bootstrap listener and stays in the foreground until Ctrl+C/SIGTERM. Opening the default browser is only a launcher handoff, not proof that the page loaded. Closing the tab does not terminate this host; it remains available for refresh until explicitly stopped.
- `app` owns the same listener plus one Kauri native window. Window close or a cooperative process signal releases these frontend resources and revokes browser admission; it does not stop the backend or accepted work. The backend never owns the website or opens the window.
- The executable embeds the same production website described below, including fonts and notices. It serves resources directly from bounded readers, without extracting an adjacent website directory.
- `app` still requires a working system WebView/display: WKWebView on macOS, GTK3/WebKitGTK4.1 on Linux, or Microsoft Edge WebView2 Runtime on Windows. Browser engines are not embedded. Linux loads its GUI libraries only when `app` runs; they are not mandatory dependencies of TUI/backend/`web`.
- Native management authentication registers a random browser-only credential bound to the frontend's exact Origin, backend instance and canonical initial directory. The page receives this scoped credential only after a capability-authenticated same-origin JSON bootstrap. It cannot authorize management. Neither management nor browser credentials appear in URLs, static resources or product diagnostics.
- The URL fragment contains only the separate frontend capability. HTML removes it before Wasm starts and retains it in tab-scoped `sessionStorage` for refresh. The scoped backend credential stays in page memory. No cookie is set or used; this is not a promise of memory-only frontend authorization: sessionStorage can survive a browser's session restore. Denied storage permits the initial launch but cannot support refresh.
- Wasm connects directly to the backend's `/browser/rpc` and `/heartbeat`, not through the frontend host. The backend provides only business services and browser admission. Management routes reject browser Origins; heartbeat CORS and the WebSocket hello enforce exact Origin/Host, instance and versions.
- Admission expires after 60 seconds without an authenticated browser heartbeat. Static GETs, bootstrap POSTs, Origin probes, socket existence and an idle Native host never renew backend lifetime or this admission lease. The connected browser normally renews within 20 seconds; suspended/background pages cannot promise continued admission. Refresh reconnects while both the host and admission remain alive; an expired lease requires running a new command.
- Cooperative host shutdown attempts to revoke admission and its transports. A failed revoke or killed/crashed host is bounded by the lease instead. An ended/restarted backend requires a new command too. Old pages never discover or retarget a replacement. A business mismatch or missing `browser_grant_protocol` fails explicitly; the launcher does not stop or replace an older backend automatically.
- Initial directory binding is not a filesystem sandbox. This trusts the local OS/listener, as does the manual mode below.

Build the matching Native executable using the verified toolchain, for example `:app-cli:linkReleaseExecutableLinuxX64`. Its task graph builds the production website, packs it and links the target resource object. `kodex.nodeCommand` and `kodex.assetClang` select the build-time Node/Clang executables; these are not product runtime requirements.

## Build and distribute

Use the repository's verified JDK/Gradle Daemon and toolchain conventions:

```sh
./gradlew :app-web:webArchive checkSpecApi
```

- Website: `app/web/build/dist/wasmJs/productionExecutable/`.
- Archive: `app/web/build/distributions/kodex-web-<application-version>.zip`.
- The archive contains `version.txt` and a `SHA256SUMS` manifest covering every website file. After extraction, `sha256sum -c SHA256SUMS` verifies completeness, not publisher identity.
- Serve the entire extracted tree, including packaged fonts/resources. Do not copy only the `.wasm` file.
- Serve `.wasm` as `application/wasm`, JavaScript as JavaScript, and preserve relative resource paths. HTML/version metadata should revalidate; deploy assets as one versioned directory, never mix releases. No service worker is installed. The independent manual-import mode keeps its private record only in page memory.
- Keep the static host bound to loopback. A simple static HTTP server is sufficient; no Node business backend is needed.

## Manual connection from an independent website

1. Serve the package at an HTTP origin such as `http://127.0.0.1:18795`.
2. Start the matching Native backend explicitly:

   ```sh
   kodex backend start --web-origin http://127.0.0.1:18795
   ```

   Repeat `--web-origin` for each trusted local origin. This manual cross-origin path is disabled without the option; the authenticated built-in launch path is separate. Origins must be HTTP `127.0.0.1` or `localhost`, with no path, query or fragment; port is part of the allowlist.
3. Open the website and choose the backend's current private `<Kodex Home>/.locks/backend/connection.json` using the file picker. Treat the file as a credential, not a shareable address.
4. Optionally enter an absolute initial directory on that backend. Blank selects this local backend's Home; the backend validates and canonicalizes the directory before Application creation.

- No automatic Home/file discovery, credential URL, localStorage or IndexedDB persistence. Reload requires selecting the private record again.
- A currently running backend is not reconfigured by the website or by another start command. If it was started without the trusted origin, use the normal operator-controlled lifecycle to restart it; the browser never stops or replaces it.
- The native TUI and management commands keep their existing header-authenticated paths.
- Browser admission checks the exact Origin/Host, backend instance, transport version and business compatibility before registering the original RPC services. Only `/heartbeat` has narrow browser CORS; management routes do not.
- Renewal belongs to the connected frontend, independently of one WebSocket. Disconnect stops renewal, not accepted backend work. Streaming observations rebind after exact-instance recovery; one-shot writes are never replayed when their result is unknown.
- Browsers can throttle/suspend background tabs. A suspended page does not guarantee renewal or prevent the backend's configured idle expiry. After restart or explicit rejection, select the new connection record rather than retargeting old drafts.

## Trust and unavailable capabilities

- Trust both the Web package/static origin and the current local OS/listener. Selecting the private record gives that page backend access. Loopback HTTP does not authenticate an adversarial replacement listener or protect against a compromised local OS; do not expose this mode to remote or untrusted shared hosts.
- A private record is only connection input, not proof that its old process still owns its port. This mode is not a substitute for TLS/server identity and an independently designed cloud login/session protocol.
- Directory browsing, context-source path editing, OpenAI/MCP provider sign-in and frontend shell Hooks are unavailable. Their controls are disabled or replaced with an explicit explanation; existing backend-owned credentials/settings remain usable.
- Initial directory validation is not a filesystem sandbox or backend authorization boundary. Model/tool execution and storage still belong to the backend.
- History prose and URLs are selectable plain text; images use safe references/placeholders rather than inline previews. This migration does not add a rich-media renderer.
- The [pinned Wasm UI binary](../../dependency-fix/compose-ui/README.md) repairs Compose popup-owner restoration, disabled AX mapping, stale click dispatch and explicit control role/state mapping. Normal builds resolve the immutable GitHub package, not a source composite; the official UI root and other variants remain unchanged. The original strict browser assertions remain enabled; this is not complete accessibility conformance or acceptance on untested platforms.

## Validation boundaries

- Early launch-ticket removal: `node --test app/web/scripts/test-local-bootstrap.mjs`, without a browser or backend.
- Single-file Linux entry: `node app/web/scripts/check-local-entry.mjs /path/to/kodex-cli.kexe /path/to/evidence`. This copies only the executable, creates a temporary Home, blocks runtime toolchain commands and owns a headless Chrome profile. Its private `xdg-open` adapter does not change the user's default browser configuration. The updated driver checks foreground-host lifetime, separate resource/business ports, no cookie, scoped admission, direct RPC, refresh and revocation without stopping the backend. It has not yet been run against the refactored Native binary.
- Protocol/admission and original Native paths: `:app-rpc-protocol:linuxX64Test`, `:rpc-local-server:linuxX64Test`, `:app-cli:linuxX64Test`.
- Connection routing/recovery: `:rpc-browser-client:wasmJsBrowserTest`, with a headless browser only as the batch test runtime.
- Shared pages/capability degradation: renderer test-support's raster/offscreen suite, not repeated full business-browser sessions.
- Framework compatibility: the independent producer's `binary-probe` build exercises raster owners and their DOM listener against staged Maven binaries, including pre-mirror dispatch and layout reuse. The publication workflow requires all four strict regressions and checks official-root/fork-leaf selection.
- Product integration: `app-web-test-support-backend`'s separate `browser` executable wraps production `runBackendHost` with a temporary Home and offline SDK. It does not replace the admission route or business graph, and is never included in this package.

For Linux product integration, explicitly set `TMPDIR=/tmp`, build
`:app-web-test-support-backend:linkBrowserDebugExecutableLinuxX64`, and run its
`build/bin/linuxX64/browserDebugExecutable/kodex-product-browser-fixture.kexe`.
It prints only the private record's temporary path. Serve the extracted archive on
`127.0.0.1:18795`, then run with the NVM-managed Node:

```sh
KODEX_BROWSER_RECORD=/tmp/kodex-product-browser-<id>/.locks/backend/connection.json \
  node app/web/scripts/check-product.mjs build/wasm/package.json /path/to/evidence
```

- The driver accepts only this temporary fixture path. It owns its headless Chrome profile and removes it on exit.
- It checks product admission/renewal, real RPC/streaming, continuous cursor events, draft-preserving exact-instance recovery, unavailable controls, disconnect renewal cancellation and explicit import after reload.
- Recovery closes a real browser-owned WebSocket through a driver-only preloaded wrapper. It changes neither product assets nor backend routes; it is not proof of every offline/packet-loss timing. Chrome's offline emulation did not close the already-open WebSocket in the first run.
- Functional results/screenshots are recorded separately from the final strict disabled-state accessibility assertion. A functional success followed by nonzero exit is not a whole-script pass; raster semantics do not replace browser AX acceptance.
- An empty line on the fixture's stdin stops it and removes its temporary Home. `restart` instead stops and restarts the production host instance in the same test process/Home; it is not an OS-process restart test.
- `KODEX_BROWSER_FIXTURE_DELAY_MS` optionally slows each offline response part (1–5000 ms, default 300) for targeted running/readonly checks. These controls exist only in test-support, not the Web package or production protocol. Never substitute a real Home or user credentials.

The lifecycle driver owns the static server, temporary-Home backend and isolated browser itself:

```sh
node app/web/scripts/check-lifecycle.mjs \
  app/web/test-support/backend/build/bin/linuxX64/browserDebugExecutable/kodex-product-browser-fixture.kexe \
  /path/to/extracted/website /path/to/evidence
```

- Keep `127.0.0.1:18795` free. It checks independent frontend drafts/renewal, explicit import after an instance restart, and actual idle expiry while the sole page is frozen.
- `KODEX_LIFECYCLE_RESTART_ONLY=1` skips the long renewal/expiry waits for diagnosis. Its result is explicitly `restart-only`, not full lifecycle acceptance.
- `KODEX_LIFECYCLE_FREEZE_ONLY=1` skips only the independent-renewal wait. It still waits for actual expiry, returns the unfrozen page to the foreground before UI actions, and reports `freeze-only`, not a full pass. Targeted modes are mutually exclusive.
- Headless Chrome may retain `visibilityState=hidden` after forced unfreeze and `bringToFront`, with animation frames still paused; a plain HTML control reproduces this. After expiry, the driver restores a visible/focused host using CDP focus emulation before testing user interaction. It does not enable that emulation during the freeze or alter product callbacks.
- To inspect that host behavior without Wasm, a backend or credentials, run `node app/web/scripts/check-lifecycle-host.mjs`. It prints visibility, focus, animation-frame and ordinary DOM-click observations; it is diagnostic, not product acceptance.
- Expected fetch rejection is contained in connection recovery; cancellation still releases the connection owner. Runtime exceptions remain a strict failure, not an expected result to filter out.

## Historical platform evidence — before frontend-host decoupling

- The results below apply to the preceding Daemon-hosted implementation. They are not acceptance of the new resource/bootstrap ownership or scoped admission. MD3, HTML editing and Kauri implementation are preserved by this refactor, but their integrated behavior still needs a matching rebuild and isolated execution.

- Historical Linux single-file CLI: copied executable in a temporary Home, no adjacent assets, automatic backend reuse/start, cookie-authenticated original RPC, reload and independent browser/backend exit. Runtime Node/Java commands were blocked by the test launcher. Real `xdg-open` was separately checked with an isolated MIME default and owned Sway Chrome, not the daily browser.
- Linux WebKitGTK 2.52.6 in embedded Sway: automatic full-root connection, native Fcitx Pinyin/commit and composition Enter protection, native clipboard roundtrip, settings focus return, window close and cooperative SIGTERM. The final window cleanup no longer emits the reproduced disconnected-signal criticals. This does not establish a minimum WebKitGTK version.
- Linux Chrome 156 headless: optimized product/root integration, exact-instance restart, independent renewal, frozen-page idle expiry and explicit recovery; 900×700 and 1280×1000 desktop geometry samples.
- Linux Chrome 155 in embedded Sway: real Fcitx Pinyin/commit and Enter guard, native clipboard roundtrip, root focus return and actual read-only History copy.
- These checks do not establish a minimum supported browser version, mobile support, Firefox/Safari coverage, macOS/Windows acceptance or complete accessibility conformance. Keep those targets explicit when expanding support.
