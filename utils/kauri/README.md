# Kauri primitives for Kodex

- Imported from the user's `Kauri/framework` at commit `5829d2c9eceee05f3fa36eb896fc3dd8acb3f7d1`, with the resource packer/tests from that repository's `scripts/`.
- This is `utils/kauri` (`:utils-kauri`), not the Kauri demo application. It owns resource reading and one system WebView window; callers own static serving, authentication, typed RPC, backend work and shutdown.
- Exported contracts/KDoc are in `src/specMain`; Native implementations retain the `dev.kauri.framework` namespace and original native ownership/callback policies.
- `openEmbeddedResource` reads the linked pack with independent, bounded `Source` readers. The final executable must link the object produced from its own frontend distribution; framework tests do not need application assets.
- `WebViewWindow` constructs without native GUI resources. Its main-thread `run()` is single-use and returns after native cleanup; `requestClose()` does not stop a server or backend.

## Platform adaptations

- Linux declarations retain only ABI types. GTK3/WebKitGTK4.1 function addresses are resolved at first actual window run, not linked as mandatory startup dependencies. Successful handles and initialization failure remain cached for process lifetime; callback code is never unloaded.
- Linux cleanup checks whether each signal handler remains connected before disconnecting it; GTK destruction may already have removed those handlers. Native object references remain held until callback cleanup finishes.
- Linux fills unhandled Ctrl+Z, Ctrl+Shift+Z and Ctrl+Y at the window boundary using WebKit's native Undo/Redo commands. The after-handler respects GTK/input-method handling, WebView focus and EditorState availability; it does not inject JavaScript or implement a second undo stack.
- macOS retains AppKit/WKWebView. Windows retains the upstream trusted-path WebView2 Runtime discovery, COM callback ownership and five-argument internal compatibility entry. Runtime availability and target execution require separate verification.
- System WebView engines are not bundled. A resource pack or successful link is not proof of Wasm/GUI runtime support.
- The CLI consumes these primitives for `web`/`app`; it owns Daemon bootstrap and launch authorization. Closing the window does not stop that independent Daemon.

## Validation

- `node --test utils/kauri/scripts/test-embedding.mjs` checks deterministic packing, unsupported paths, symlinks, missing index and source-map exclusion.
- `:utils-kauri:linuxX64Test` covers real linked fixture resources, parsing/readers and non-GUI window lifetime/thread guards. Set `-Pkodex.assetClang=/absolute/path/to/clang` when the verified compiler is not `/usr/bin/clang`; `kodex.nodeCommand` selects the NVM-managed Node. These are ordinary Native tests, not full browser sessions.
- Inspect the final test/executable dependency table to verify absence of mandatory GTK/WebKit entries; merely having libraries installed locally is not a headless compatibility test.
- Linux shortcut mapping tests cover lock modifiers, both redo chords and unrelated-key rejection without loading GTK. Owned WebKit execution separately checks native copy/paste, cut, undo/redo and read-only protection; a DOM editing command is not proof that an OS shortcut works.
- Real window execution, callback cleanup and Wasm frontend integration need owned graphical runners. Record target results separately; no imported upstream GUI result counts as Kodex acceptance.

## Attribution

- Original reference: [Kauri](https://github.com/Stream29/kauri-demo), with current-versus-historical validation maintained in that repository.
- Microsoft WebView2 SDK `1.0.3650.58` declaration headers are preserved verbatim. The accompanying redistribution notice is [LICENSE-WebView2-SDK.txt](LICENSE-WebView2-SDK.txt); include it with final binary notices.
- The Web distribution and embedded resource pack contain the stable [NOTICE.md](NOTICE.md) and SDK notice, rather than this development guide.
- Local changes are spec/actual exports, Kodex build wiring, portable resource test placement, script path resolution, lazy Linux runtime binding and unhandled GTK editing shortcuts. No Kauri source repository, application service, frontend or release pipeline is modified.
