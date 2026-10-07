# Fork package CI handoff

- **IMPLEMENTATION READY / VALIDATION PENDING.**
- Worker scope: these scripts and the three `fork-packages-*.yml` workflows.
- SDK JS closure delta (Session 552): only these scripts and the MCP workflow;
  other forks keep the original five targets. All validation is coordinator-owned.
- No worker tests, Gradle/IDE, publication, commits/push, branch or gitlink edits.
  The coordinator owns deployment, credentials, consumer settings and every real run.
- Initial product baseline: Kodex `837494340036f433e5320fabedccd874b0f320e7`
  (v0.4.10). Gitlinks, not another lock file, supply publication identity.

## Coordinates and task sets

| Fork | Original group / module closure | Initial immutable version |
| --- | --- | --- |
| Mosaic | `com.jakewharton.mosaic`, `mosaic-{runtime,animation,testing,terminal,tty-terminal,tty}` | `0.19.0-SNAPSHOT-kodex.7b1a412918f7` |
| MCP | `io.modelcontextprotocol`, `kotlin-sdk-{core,client,server}` | `0.14.0-kodex.53f717661712` |
| Lucene | `org.gnit.lucene-kmp`, `lucene-kmp-core` (project `:core`) | `10.2.0-alpha14-kodex.549f8afc9570` |

- Version = the **literal** pinned `VERSION_NAME`/`version` + `-kodex.` + first
  12 commit characters. Mosaic's embedded `SNAPSHOT` is retained; the resulting
  version does not end in `-SNAPSHOT` and is not a timestamped Maven snapshot.
- Mosaic commit `7b1a412918f7bc24db847343480f010242939788`;
  MCP `53f717661712d618cd4822d1760392e903989ead`;
  Lucene `549f8afc9570bd4289f1047653d4cd6dc9665bbd`.
- Matrix tasks are the Cartesian product of each fork's project closure and its
  host publications, using `:<project>:publish<Publication>PublicationToForkStagingRepository`.
  There are 36 Mosaic, **21 MCP** and 6 Lucene tasks across hosts.
- Read-only baseline catalog/module-use audit confirmed direct runtime/animation/
  testing, MCP client/server and Lucene core usage. The declared package closure
  adds Mosaic terminal/tty-terminal/tty and MCP core; SDK umbrella and Lucene
  test-framework/codecs are not production dependencies.

| Host | Standard runner | Publications | Binary smoke tasks |
| --- | --- | --- | --- |
| Linux x64 | `ubuntu-24.04` | `LinuxX64`, `LinuxArm64` | `verifyForkJvm`, `runDebugExecutableLinuxX64`, `compileKotlinLinuxArm64`; **MCP additionally `compileKotlinJs`, `jsNodeDevelopmentRun`** |
| macOS Arm64 | `macos-14` | `KotlinMultiplatform`, `Jvm`, `MacosArm64`; **MCP additionally `Js`** | `verifyForkJvm`, `runDebugExecutableMacosArm64` |
| Windows x64 | `windows-2022` | `MingwX64` | `verifyForkJvm`, `runDebugExecutableMingwX64` |

- Mosaic additionally runs `verifyForkJvmJni` with an explicit Java21 launcher
  on all three hosts. Java25 tests the real multi-release FFM path; it is not
  a substitute for exercising the Java21 JNI loader.
- macOS is the **only authority for root/JVM artifacts and MCP JS**, including root POM and
  metadata. Other hosts contribute disjoint target publications, never competing
  root artifact lists. Root metadata redirects retain the five approved targets
  **plus JS only for MCP**; target component URLs/dependency versions are checked after merging.
- **User-approved SDK exception:** Kodex's stdio and Streamable HTTP host modules
  have real JS/Node consumers. Publish `kotlin-sdk-{core,client,server}-js`, each
  with its real generated JS KLIB, source JAR, POM and Gradle module metadata.
  Existing SDK targets already generate these components; the recipe never invents
  a module or replaces its payload with a JSON redirect/fake JAR.
- MCP Mac runs four tasks per module (`KotlinMultiplatform`, `Jvm`, `MacosArm64`,
  `Js`): 12 tasks. Linux stays six (`LinuxX64`, `LinuxArm64`), Windows three
  (`MingwX64`). The SDK umbrella is still excluded.
- No Wasm, Apple mobile, macOS x64 or Android publication is advertised.
  Mosaic/Lucene do not publish JS. Excluded source sets can remain metadata prerequisites.
- All required source/project dependencies and real generated sources remain.
  Signing/javadoc attachments are excluded by the isolated recipe; metadata,
  source JARs, JNI/FFM and Native cinterop/C bitcode are not replaced by fake JARs.

## Coordinator commands

Run from the **authorized isolated remote Kodex checkout**, not the gaming machine.
No third-party Python modules are needed; CI installs Python 3.12. For a managed
developer Python environment, the workspace preference is `uv`.

```bash
# Tiny offline fixtures; only a loopback HTTP server, no Gradle/Git or package endpoint.
PYTHONDONTWRITEBYTECODE=1 python -m unittest discover \
  -s scripts/fork-packages -p 'test_*.py' -v

# Print the exact task list without running it (repeat for each fork/host).
python -B scripts/fork-packages/pipeline.py tasks --fork mosaic --host mac
python -B scripts/fork-packages/pipeline.py tasks --fork mcp --host linux
python -B scripts/fork-packages/pipeline.py tasks --fork mcp --host mac
python -B scripts/fork-packages/pipeline.py tasks --fork lucene --host windows

# Read-only verification of a merged bundle; this is not runtime or remote acceptance.
python -B scripts/fork-packages/pipeline.py verify --fork mcp \
  --input /absolute/validated-bundle --identity-sha256 <gate-output>
```

The workflows use these CLI interfaces:

```text
pipeline.py guard --fork <mosaic|mcp|lucene> --output <identity.json>
pipeline.py build --fork <fork> --host <linux|mac|windows> --output <new-sealed-dir>
pipeline.py merge --fork <fork> --input <downloaded-stage-dirs> --output <new-bundle-dir>
                 --identity-sha256 <gate-output>
smoke.py --fork <fork> --host <host> --bundle <bundle> --output <new-consumer-dir>
         --receipt <host.json> --identity-sha256 <gate-output>
publisher.py --fork <fork> --bundle <bundle> --receipts <three-receipt-dir>
             --identity-sha256 <gate-output>
```

- `guard` and `build` take `--root` and `--sha`; defaults are the checkout and
  `GITHUB_SHA`. They require initialized clean fork gitlinks. `guard` additionally
  requires a clean exact-main CI checkout, outputs the commit and an identity
  digest, fetches `kodex-submodule`, and runs `merge-base --is-ancestor`.
  Identity includes `targetClosure` (per-fork target list and host authority map);
  stages/manifests must match it. Old SDK five-target bundles fail this contract.
- A reviewed manual dispatch uses **ref `main`, input `reviewed=true`**. There is no
  arbitrary revision/commit input. Every checkout pins `github.sha`; live remote
  main must still be exactly that SHA before building and before completion.
- Workflow/script-only main pushes do **not** publish. Automatic gitlink push
  admission additionally requires a protected main ref; unprotected main is
  manual-only, with explicit `reviewed=true`. First deployment with
  unchanged gitlinks therefore needs three separately reviewed manual dispatches.
  Ordinary PRs execute only offline tests with read permissions.
- The coordinator must create the three maintenance branches at the existing
  commits first, review/deploy these files, and execute any remote CLI with the
  shared-device/JVM/lock requirements. Never use these build commands against a
  shared/default Gradle home. `GRADLE_USER_HOME` and `KONAN_DATA_DIR` must be explicit
  private siblings under the staging/consumer parent; `JAVA_HOME` is explicit.
- `publisher.py` is CI-only, takes no token argument or alternate endpoint,
  and reads `GITHUB_TOKEN` only in the minimum write-permission job. It never
  executes Gradle or fork code. Do not invoke it as a local deployment helper.

## Toolchains and first-run gates

- Each fork's original wrapper and catalog are read from its exact Git archive:
  Mosaic Gradle **9.6.1 / KGP 2.3.21**; MCP **9.6.1 / 2.4.0**;
  Lucene **9.5.1 / 2.4.0**. This recipe does not upgrade root/fork versions.
- CI installs Temurin 25 for the explicit Gradle JVM and Temurin 21 for MCP's
  original toolchain (`FORK_JDK21` is the setup-java output path). Archive outputs
  are fresh, build/configuration caches disabled, one worker and no parallel build.
- Daemon-first compilation has fallback disabled. Mosaic/MCP use Gradle 2 GiB
  plus Kotlin daemon 2 GiB; Lucene keeps Gradle 4 GiB for Native compiler work
  plus Kotlin daemon 2 GiB. Binary probes use Gradle/Kotlin daemon 2 GiB each.
  These are initial CI budgets, **not measured production tuning**.
- Mosaic JVM invokes its original Zig **0.15.1** task and jextract **1.0.0**
  plugin, including JDK22 multi-release FFM classes. macOS JNI alone uses
  `FORK_DEVELOPER_DIR=/Library/Developer/CommandLineTools` and Zig `-j2`;
  Kotlin/Native uses the runner's selected Apple SDK. Both SDK versions are recorded.
- Mosaic's cklib **0.3.5** and real main cinterop must work on all three hosts.
  MinGW headers/C compilation stay on Windows. Cross-compiling Linux Arm64 on
  Ubuntu is allowed, but is **not** an Arm64 runtime test.
- MCP's `generateLibVersion` captures the startup `-Pversion`; the real JS
  consumer asserts compiled `LIB_VERSION` equals that immutable version.
  There is no late task-input-only hook. Lucene's original AGP
  **9.0.1** still configures Android and its generated data sources. The recipe
  locates runner `ANDROID_HOME`/`ANDROID_SDK_ROOT` and uses its `sdkmanager`
  to prepare `platforms;android-36` and `build-tools;36.0.0` if missing, without
  accepting new licenses. SDK/toolchain absence fails rather than deleting targets.
  Those SDK inputs are hashed; Android is not published. Build Tools 36.0.0 follows
  [the original AGP compatibility requirements](https://developer.android.com/build/releases/agp-9-0-0-release-notes).
- The SDK isolated publisher sets its root name to `kotlin-mcp-sdk-fork` in
  `settingsEvaluated`, avoiding the known `getKotlinSdk()` accessor collision
  without changing pinned fork files, projects or publication coordinates.
- **SDK Linux smoke adds `compileKotlinJs` and `jsNodeDevelopmentRun`** after the
  unchanged JVM/linuxX64/Linux Arm64 compile gates. JVM/Native probes remain in
  their platform source sets on this host; the JS probe uses a suspend main,
  not JS-incompatible `runBlocking`.
- MCP smoke CI installs Node **22.14.0** via standard `actions/setup-node`.
  Script callers must provide `node` on PATH at least **22.14.0**; missing/older
  Node fails, never skips JS. Ordinary KGP `NodeJsEnvSpec` uses that executable
  with download disabled. macOS JS library publication only compiles KLIBs,
  so it does not need a second Node runtime gate.
- The real JS probe compiles coroutine `StdioClientTransport` source/sink/error,
  core `JSONRPCNotification`/`Implementation`, `Client`, `Server`/`ServerOptions`,
  `StdioServerTransport` and `StreamableHttpClientTransport`. Node sends and
  flushes a real SDK stdio frame, verifies I/O release and generated `LIB_VERSION`,
  and constructs ordinary Ktor `HttpClient { install(SSE) }` using the matching
  `ktor-client-js:3.5.1` engine. It opens no HTTP connection or child process;
  this is basic lifecycle/API acceptance, not full transport integration.
- Standard macOS has limited memory/disk; cold compiler/LLVM/jextract preparation
  and daemon budgets may expose host-specific failures. Windows console availability
  may expose a Mosaic smoke failure. Return the exact failing gate to the worker
  for a scoped fix; do not skip it or call an unrun workflow green.
- Actual Kodex default-consumer acceptance, IDEA behavior, full regressions and
  Linux Arm64 runtime remain coordinator-owned. The isolated probes are not those gates.

## Manifest, merge and immutable writer

- Stage artifacts contain only `repo/`, `stage.json` and `toolchain.json`.
  They retain full commit/tree, repository/maintenance branch, upstream version,
  script/own-workflow recipe hashes, tasks, host/toolchain/source-input hashes,
  sizes and SHA-256.
- CI Git process configuration disables CRLF conversion without editing global
  Git settings. Safe pinned source-tree document links are preserved (MCP
  `CLAUDE.md`); no symlinks are accepted in staged/merged Maven outputs.
- The merger rejects mixed identities/recipes/toolchains, duplicate host files,
  unexpected coordinates/attachments and incomplete POM/GMM/payload closure.
  It also rejects path traversal, symlinks, nonregular files and unsafe/oversized
  ZIP payloads. Native manifests must identify the right target and compiler.
- SDK JS metadata requires JS/IR attributes and root `available-at` redirects,
  versioned client/server core dependencies in both POM and GMM, Kotlin sources
  and nonempty IR/linkdata inside real KLIB ZIPs. Its manifest must have
  `builtins_platform=JS` and no `native_targets`; Native keeps its target check.
- SDK toolchain evidence records JS and Native compiler archives separately
  (`compilerArchives`, version resource plus SHA-256). Versions come from
  `META-INF/compiler.version` in KGP's compiler-embeddable JAR and the private
  Native distribution's compiler JAR, **not** from stdlib manifests or the
  assumption that KGP/Maven/compiler build strings are identical.
  Each SDK KLIB must match its recorded platform compiler. All three forks
  record the actual Native compiler archive and loaded KMP plugin JAR hash.
  Declared Zig/plugin versions are not measurements. Missing cache
  evidence or mismatches fail closed; the coordinator must check real host layouts.
- The immutable build manifest covers all Maven payload files. Remote transport
  additionally includes regenerated SHA-1/256/512/MD5 sidecars and a manifest
  classifier attached to the alphabetically first root module:
  `<artifact>-<version>-kodex-manifest.json`, plus SHA-256/512 sidecars.
- `remoteVerified=false` in that immutable **build** manifest is intentional:
  presence of the file is not a completion API or proof of remote acceptance.
  Completion is established by reading **every expected remote file** and comparing
  exact bytes, with all three smoke receipts bound to the same manifest.
- Existing complete version: skip **only** after all remote bytes, including
  manifest/sidecars, match. Existing partial/different version: fail before writes;
  do not delete, resume, replace or overwrite any user version.
- Fresh version: preflight all expected paths absent; recheck each path before PUT,
  never PUT over a present file. Registry-generated sidecars can be skipped only
  when byte-identical. Payloads precede POM/module files; manifest is last.
  Reads retry visibility verification at most six times, 10 seconds apart.
- Basic auth uses the repository `GITHUB_TOKEN`. Redirects fail closed rather
  than forwarding credentials; logs never include auth headers or response bodies.
  A registry redirect/permission failure is a concrete deployment gate to diagnose.
- Maven PUT is **not atomic**, and server support for `If-None-Match` is not assumed.
  A cancellation, stale main, transport failure or final mismatch can leave a partial
  version. No completion is claimed. Keep the old consumer pin, retain the validated
  bundle, audit with a maintainer, and obtain separate approval for remediation or
  publish a new reviewed fork commit/version. No automated deletion is provided.
- Workflow and writer concurrency serialize each fork across hashes and do not
  automatically cancel a running writer. Duplicate matrix jobs are also keyed by
  fork/hash/host. Pending runs can be superseded; obsolete runs fail live-main gates.
  External writers are outside Maven's transactional guarantees and must not race
  the approved central writer. Main is currently unprotected: exact-SHA checks do
  **not** replace repository review/protection policy, which the coordinator owns.
- CI artifacts expire after seven days; logs remain under repository retention.
  Package versions are not automatically deleted. Save failure evidence before
  artifact expiry. A recipe/toolchain change cannot overwrite this same version.
- This JS delta retains the immutable version names above because the coordinator
  confirms the initial versions are not yet published. Any existing five-target,
  partial or different version still fails preflight; no overwrite exception was added.
- Offline JS fixtures cover payload/source loss, wrong platform/compiler,
  missing/invalid root redirects, POM/GMM dependency loss, host/task drift,
  target-bound guard identity and refusing receipts without JS compilation.
  Original loopback immutable-writer/security tests remain; all tests and real
  three-host/JS gates are **written, not executed by this worker**.

## Evidence and official mechanisms

- Prior research is input, not reusable production bytes:
  `xiaoxin-ubuntu:~/ACodeSpace/demo/kodex-gradle-research-445/fork-publication/`.
  The worker read `publish.init.gradle`, `batch-mcp.sh`, `GRAPH.md`, `REPORT.md` and
  selected actual publication metadata/payload listings over read-only SSH.
- Root/target publication separation and Apple-host gates follow the
  [Kotlin publication documentation](https://kotlinlang.org/docs/multiplatform/multiplatform-publish-lib-setup.html).
- Public submodule SSH-to-HTTPS behavior without a key follows
  [actions/checkout](https://github.com/actions/checkout). A process-local read-only
  URL bridge validates origin identity after checkout removes its temporary credentials.
- Authentication uses the repository workflow token as described by the
  [GitHub Gradle registry documentation](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-gradle-registry).
- Labels are standard runners from
  [GitHub's runner reference](https://docs.github.com/en/actions/reference/runners/github-hosted-runners);
  public standard-runner minutes do not imply unlimited Packages/artifact storage.
