# Gradle development

## Normal builds

- Use Java 25 for Gradle and compiler tooling; JVM is not a Kodex product target.
- CLI/backend and native client conventions target `linuxX64`, `linuxArm64`,
  `macosArm64` and `mingwX64`. Portable Shared libraries retain their separate
  JS/Wasm/iOS coverage; this does not provide a Node backend.
- Mosaic, Lucene KMP and the MCP Kotlin SDK are fixed binary dependencies.
  Their versions are declared once in `gradle/libs.versions.toml`.
- Normal builds do not configure or publish the three source submodules.
  A clone without initialized fork submodules can use the binary build.
- No automatic dirty-source or Maven Local fallback is enabled. Do not restore
  a JVM/Node product target as a development shortcut.

## Package access

GitHub's Maven registry requires authentication, including for public packages.
Create a **classic PAT with only `read:packages`**, a finite expiry, and package
access. Keep it outside this checkout; do not use a write token for development.
[GitHub Gradle authentication](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-gradle-registry).

Add these entries to your private `~/.gradle/gradle.properties`, preserving
its other settings:

```properties
gpr.user=YOUR_GITHUB_LOGIN
gpr.key=YOUR_READ_ONLY_CLASSIC_PAT
```

- Restrict that file to your user (`chmod 600 ~/.gradle/gradle.properties`).
  Never commit it, print it in a diagnostic, or include it in a shared snapshot.
- CI may instead supply `GITHUB_ACTOR` and a repository token with
  `packages: read`. User-level Gradle properties take precedence.
- Existing `gh` authentication is separate and need not be replaced. A `gh`
  OAuth login alone does not provide this Gradle credential configuration.
- On expiry or revocation, replace the private token. A 401/403 can indicate
  credential/access problems; a 404 can also mean an unavailable immutable pin.
  Inspect the fixed coordinate and package evidence, not the token value.
- Missing credentials or a missing version fail binary resolution. Neither
  condition triggers a source rebuild or a different repository fallback.

Only the three fork groups route exclusively to
`https://maven.pkg.github.com/Stream29/Kodex`; other dependencies retain their
existing repositories. Basic authentication is scoped to that ordinary Gradle
repository. [Gradle exclusive content](https://docs.gradle.org/current/userguide/filtering_repository_content.html).

## Commands

From the Kodex checkout, for example:

```sh
./gradlew help
# Linux x64 host:
./gradlew :mcp-contract:linuxX64Test :mcp-backend:linuxX64Test
./gradlew :app-component-history-view:linuxX64Test
./gradlew :app-cli:linkReleaseExecutableLinuxX64
```

Keep the actual existing Gradle Daemon JVM explicit when using an agent or an
isolated validation script. CLI configuration time is not IDEA Sync or indexing
time. Resource and build-logic changes require separate measurements, not
guarantees supplied by binary adoption. Follow the declared product/portable
target sets; there is no target-profile switch.

## Adding a module

- Declare its real directory explicitly in `settings.gradle.kts`; placing a
  new build script on disk no longer adds a project automatically.
- A root `moduleTree` keeps its existing namespace project by default;
  `includeGroupingRoot = false` makes `mcp` a directory-only group. A nested
  `moduleTree` registers itself only if it has a build script; otherwise it
  supplies the physical path for its declared children.
- Use `module("name")` for a real leaf with `build.gradle.kts`.
  Flat project IDs still join path segments with `-`.
- Use business directory names, not `spec`/`impl` grouping segments.
  Use `contract` for necessary cycle-breaking or shared frontend/backend
  protocol boundaries; actual system adapters keep their business platform boundary.
- Invalid names, missing leaves, duplicate physical declarations and colliding
  flat project IDs fail configuration instead of silently replacing a project.

## Adding tests

- Modules with tests explicitly apply `id("kodex.kmp-tests")` alongside their
  existing KMP convention. This applies the existing TestBalloon plugin and
  common test dependencies together; platform-specific tests inherit them too.
- Add the convention before adding ordinary or generated test sources, even
  if `commonTest` itself is empty. View, ViewModel, portable JS, Native and custom
  test source sets follow the same rule.
- Base conventions still declare their complete targets and test source sets.
  Empty modules do not load the test framework/compiler plugin merely because
  they use KMP. This does not disable test tasks or remove platform support.
- Run the module's actual test task after opting in. A cached or `NO-SOURCE`
  task is not evidence that newly added tests execute.
- Retired JVM source/test directories are not active product targets. Port
  needed coverage to Native rather than claiming those tests still execute.

## Build conventions

- Business KMP modules apply `kodex.kmp-spec-impl` before their target/renderer
  convention. Contracts live in `specMain`; `commonMain` depends on it.
  Implementation-only modules may leave `specMain` empty only when they have
  no module-owned public export.
- A widened business module keeps narrow implementation inputs in the applicable
  intermediate source sets. `kodex.kmp-cli-host-layer` creates `cliHostMain/Test`
  for four desktop Native targets, retaining `desktopNativeMain/Test` below it;
  it does not add targets or expose CLI factories on JS, Wasm or iOS.
- `kodex.ktor-native-network-override` preserves the existing four desktop Native
  network pins independently of the enclosing module's target convention.
- Conventions and `GenerateKodexVersion` live in `build-logic`, explicitly
  included by `pluginManagement`. It reads KGP and compiler-plugin versions from
  the root version catalog. `kodex.kmp-viewmodel` keeps the CLI target convention
  without injecting a DI compiler plugin or container dependencies.
- Keep the root Kotlin multiplatform, serialization and Compose declarations
  with `apply false`. They anchor the shared plugin classpath; removing them
  has reproduced duplicate KGP loading in the composite.
- Actual kRPC consumers, including test-only consumers, explicitly apply
  `id("kodex.krpc-utils-patch")`. It retains the existing Native utility patch
  without attaching substitution rules to unrelated projects. When adding a
  consumer, check the reverse project-dependency closure and apply the same
  convention to downstream consumers; the current list is not permanent.
- `kotlin.daemon.useFallbackStrategy=false` makes a failed Kotlin daemon
  connection/startup fail the build instead of silently compiling in the
  Gradle process. Diagnose the daemon failure rather than removing the policy.
  The existing Gradle/Kotlin maximum heaps are unchanged.
- These changes do not certify an IDEA OOM fix or faster Sync. Complete IDE
  import and actual source navigation remain separate validation gates.

## Working on a fork

- Use the fork's `kodex-submodule` maintenance line and its actual toolchain.
- For an integration experiment, manually add the required source
  `includeBuild` and genuine substitutions, then restore binary settings.
  Do not commit that temporary opt-in as the default.
- The pinned SDK already uses the distinct `kotlin-mcp-sdk-fork` root name.
  Its historical source accessor collision is not evidence that a binary
  package is missing.
  Composite compatibility is a separate gate from ordinary binary consumption.
- A dirty fork is never automatically published by settings or normal tasks.
  Commit/review the new gitlink, run its dedicated CI, verify the entire package,
  and only then change the catalog version in a separate consumer commit.
- The publisher seals full commit/tree, real host/target/toolchain evidence and
  checksums. Maven uploads are not transactional: a partial/different version
  stops without deletion, overwrite or blind resume.
  See [publication procedures](../scripts/fork-packages/README.md).
