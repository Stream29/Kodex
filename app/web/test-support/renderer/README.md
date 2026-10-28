# Browser renderer fixture

- This is a test entry, not `kodex web` or a production authentication protocol.
- The browser uses shared widgets, Application navigation, NewSession, Composer and the product History list. Stable/pending tools, Patch, read-only forms and streaming content use the same shared composition as Native.
- The Native backend uses a temporary Home and an offline model. It never uses provider credentials.
- Remote directory browsing, context-path policy, production cloud authentication, provider OAuth and the complete Web application host are not delivered by this fixture. Controlled reconnection is a test driver, not a production cloud connection policy.

## Build

Use the running Gradle daemon's JVM and JVM arguments. Run Native and Wasm builds separately to limit compiler memory.

| Task | Output |
| --- | --- |
| `:app-web-test-support-renderer:wasmJsBrowserDistribution` | `app/web/test-support/renderer/build/dist/wasmJs/productionExecutable/` |
| `:app-web-test-support-renderer:wasmJsBrowserTest` | Connection unit tests and offscreen component, settings and History tests |
| `:app-web-test-support-backend:linkDebugExecutableLinuxX64` | `app/web/test-support/backend/build/bin/linuxX64/debugExecutable/kodex-web-fixture.kexe` |
| `:app-web-test-support-renderer:checkSpecApiModule` | Exported API / source-set checks |

- Optional `kodex.binaryenCommand` and `kodex.nodeCommand` Gradle properties select explicitly installed tools.
- Build development and production distributions in separate Gradle invocations; the Kotlin plugin shares npm outputs between them.
- The following commands run from the product root with NVM-managed Node. Browser scripts reuse the generated `ws` package; do not install a second dependency set.

## Test layers

- Run component regressions with `wasmJsBrowserTest`, not by opening the complete fixture for each case.
- `OffscreenScene` uses the pinned Compose `ImageComposeScene` raster surface: no `ComposeViewport`, business connection or backend is mounted. Tests assert PNG output, semantic content, pointer activation and changed pixels together with the original state.
- The helper serializes scenes and drives real frames until the documented snapshot/invalidations idle condition holds. Its scoped navigation input models the pinned window host's Escape-to-Back dispatch and is removed when the scene closes; raw scene key input alone does not provide that host routing.
- Async composition failures fail the scene instead of silently yielding an empty/stale image. The Karma task also fails on an empty suite.
- The History scene uses the product list, real ViewModel and in-memory storage containing 640 messages. Scene scrolling exercises actual visible children, bounded paging, retained child identity and widget position across remount, exact item/Latest acknowledgment and unmount lifetime.
- Other History scenes cover real folded WorkGroup children, controlled tool/Patch expansion, transient ordering and destructive window replacement. Tool cases check safe media references, secret-answer redaction and lazy detail sections; Patch pagination uses a semantic action when its button is outside the raster viewport.
- The History menu scene right-clicks the actual selectable message text, checks one application popup and preserves the default selection menu when no application callback is provided.
- Index scenes cover independent read handles on a real owner, captured revision/menu identity, optional timestamps, lazy rows and hover redaction. Sidebar scenes check controlled content, exact borrowed children and Shell handles; they do not establish complete root popup arbitration or a cloud deployment.
- Settings cases exercise shared pages, controlled fields, dropdown layers, exact-token forms and modal hosts. Raster scenes explicitly supply `RasterTextInput` through the scoped value-field renderer; interactive hosts still use the real platform editor. `SetText` actions check shared synchronous draft/submit wiring, not DOM placement, native focus, physical keys or IME. Native menu placement and existing component APIs remain covered by the original Native tests.
- The Settings root's Rename slot test captures its exact child and dismissal callback; it does not mount the DOM editor in a raster scene. `HtmlElementView` needs the real window's interop container. Root DOM mounting/submission/focus must be checked in the browser integration layer, not inferred from this slot test.
- Widgets' small DOM tests cover overlay hit-testing, retained text/selection and canvas focus after editor detachment, including release that must not steal another input's focus. They do not mount Application or RPC.
- These Wasm tests share one headless JS/Skiko runner. They are not a browser-free Node runner or OS input-method acceptance.
- Keep targeted browser checks for production assembly, DOM editor/focus/composition and actual transport recovery. Run only the affected mode. OS IME remains a separate native-input check.

## Static-only checks

Start the static server in one terminal:

```sh
node app/web/test-support/renderer/scripts/serve.mjs \
  app/web/test-support/renderer/build/dist/wasmJs
```

It binds only `127.0.0.1:18795`, serves static files without a business proxy, and exits on SIGTERM. The production URL is `http://127.0.0.1:18795/productionExecutable/index.html`.

In another terminal, select an owned output directory:

```sh
export KODEX_FIXTURE_WEB_URL=http://127.0.0.1:18795/productionExecutable/index.html
export FIXTURE_OUTPUT="$(mktemp -d)"

KODEX_DESCRIPTOR_ONLY=1 node app/web/test-support/renderer/scripts/check-browser.mjs \
  "$PWD/build/wasm/package.json" "$FIXTURE_OUTPUT/descriptors"

KODEX_INPUT_ONLY=1 node app/web/test-support/renderer/scripts/check-browser.mjs \
  "$PWD/build/wasm/package.json" "$FIXTURE_OUTPUT/composition"

KODEX_FORM_ONLY=1 node app/web/test-support/renderer/scripts/check-browser.mjs \
  "$PWD/build/wasm/package.json" "$FIXTURE_OUTPUT/forms"
```

- Descriptor mode checks all eight real proxies and runtime serializer lookup in the optimized executable, without opening RPC.
- Input mode checks CDP composition/commit, Enter handling, bidirectional Tab and CtrlEnter/newline policy. It is not an OS input-method test.
- Form mode mounts compiled value fields without RPC: literal multiline values, DOM undo/redo across controlled echoes, Enter actions, DOM/Canvas Tab traversal, disabled rejection/identical retry, overlay state and owner remount. It also mounts the production Hook dialog with an in-memory draft; it neither saves nor executes hooks. These checks do not establish OS clipboard/IME behavior or cover every dialog's scrolling geometry.
- Its selection setup uses CDP's explicit [`selectAll` editing command](https://github.com/ChromeDevTools/devtools-protocol/blob/master/pdl/domains/Input.pdl#L129-L132), first checked on a plain HTML textarea. A synthetic Mac modifier event alone is not treated as an OS shortcut acceptance test.
- The script owns a separate headless Chrome/profile and closes/removes both in `finally`; it does not use the user's browser.

## Real RPC slice

Create one runtime-only token shared by the backend and browser check:

```sh
umask 077
node --input-type=module -e \
  'import {randomBytes} from "node:crypto"; process.stdout.write(randomBytes(32).toString("hex"));' \
  > "$FIXTURE_OUTPUT/token"
export KODEX_WEB_FIXTURE_TOKEN="$(cat "$FIXTURE_OUTPUT/token")"
```

Start the Native executable in a terminal inheriting that variable. Keep its stdin open; enter a line to stop it normally and remove its temporary Home.

In another terminal inheriting the same token:

```sh
node app/web/test-support/backend/scripts/check-handshake.mjs "$PWD/build/wasm/package.json"

node app/web/test-support/renderer/scripts/check-browser.mjs \
  "$PWD/build/wasm/package.json" "$FIXTURE_OUTPUT/business"

KODEX_RECOVERY_ONLY=1 node app/web/test-support/renderer/scripts/check-browser.mjs \
  "$PWD/build/wasm/package.json" "$FIXTURE_OUTPUT/recovery"

KODEX_ROOT_ONLY=1 node app/web/test-support/renderer/scripts/check-browser.mjs \
  "$PWD/build/wasm/package.json" "$FIXTURE_OUTPUT/root"
```

- The backend binds only `127.0.0.1:18796/fixture` and accepts only the static fixture's exact Origin/Host and candidate hello.
- The browser check injects the token via a fragment, removes it immediately, and never prints it. Do not put tokens in source, build outputs or query strings.
- The slice exercises real Application assembly, creation, streaming/committed messages, persisted Composer, exact retained-tab navigation and singleton cursor retention.
- Recovery mode drops one actual transport during a response, reactivates the retained Session before publishing its replacement and resubscribes state streams. A second fault discards delivery of an already accepted append result; write-attempt counters and recovered History check no replay or automatic resume. Only a later explicit submission may start another response.
- Discarding a delivered transport result before its caller receives it is a deterministic lost-reply simulation, not proof of every server crash or packet-loss timing. It does not change wire DTOs or add a business protocol.
- This controlled recovery check does not establish backend restart, every tool type over actual RPC, OS IME or production cloud acceptance. Keep current execution results in the task record rather than inferring them from these commands.
- Root mode mounts the full graphical root on the same isolated Application. It uses physical CDP pointer events for product menus/dialogs, checks consecutive cursor updates, real submission, direct/nested Rename, pointer/Escape dismissal and real revert-and-edit with the main editor focused. CDP text insertion is not OS IME.
- Root functional evidence is saved separately as `root-functional.json`/`.png`; the final browser AX-owner restoration gate must also pass. A functional PASS line or a raster semantics test does not make that gate successful; failures retain AX, runtime errors and a screenshot.
- Stop the backend and static server, unset the token, remove its file and delete the owned output directory when the evidence is no longer needed.
