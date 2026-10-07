# Gradle development

## Normal builds

- Use Java 25 for the Gradle JVM and regular Kodex JVM toolchains.
- Mosaic, Lucene KMP and the MCP Kotlin SDK are fixed binary dependencies.
  Their versions are declared once in `gradle/libs.versions.toml`.
- Normal builds do not configure or publish the three source submodules.
  A clone without initialized fork submodules can use the binary build.
- The existing full target model remains unchanged in this first integration.
  No automatic JVM-only, dirty-source or Maven Local fallback is enabled.

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
./gradlew :mcp-spec-stdio:jvmTest
./gradlew :app-component-history-impl-view:jvmTest
# Linux x64 host:
./gradlew :app-cli:linkReleaseExecutableLinuxX64
```

Keep the actual existing Gradle Daemon JVM explicit when using an agent or an
isolated validation script. CLI configuration time is not IDEA Sync or indexing
time. Heap, target-profile and build-logic tuning are separate measured changes,
not guarantees supplied by binary adoption.

## Working on a fork

- Use the fork's `kodex-submodule` maintenance line and its actual toolchain.
- For an integration experiment, manually add the required source
  `includeBuild` and genuine substitutions, then restore binary settings.
  Do not commit that temporary opt-in as the default.
- SDK source integration also needs the approved root-name repair; its old
  source accessor collision is not evidence that a binary package is missing.
  Composite compatibility is a separate gate from ordinary binary consumption.
- A dirty fork is never automatically published by settings or normal tasks.
  Commit/review the new gitlink, run its dedicated CI, verify the entire package,
  and only then change the catalog version in a separate consumer commit.
- The publisher seals full commit/tree, real host/target/toolchain evidence and
  checksums. Maven uploads are not transactional: a partial/different version
  stops without deletion, overwrite or blind resume.
  See [publication procedures](../scripts/fork-packages/README.md).
