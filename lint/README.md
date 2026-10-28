# Spec API lint

Run from the Kodex root with the project's JDK:

```shell
./gradlew :agent-context-agents-md:checkSpecApiModule
./gradlew :agent-context-agents-md:reportSpecApiModule
./gradlew :lint:test
```

- The lint tool and its tests still use a JVM; this does not add a JVM product target.
- `checkSpecApiModule` is strict and fails on findings. Existing APIs are not
  baselined.
- `reportSpecApiModule` writes findings without failing; success does not mean
  that the API boundary is clean.
- Each module writes reports under its own `build/reports/spec-api/`.
  `module.sarif` is the normalized non-strict report.

## Scope

Each KMP project checks its own real main-compilation source sets, excluding
`specMain`. Intermediate/platform layers and consumable main fixtures are
included. Source filters and generated-source producer dependencies are
preserved; tests and external fork builds are excluded.

Each KMP module explicitly applies `kodex.spec-api-module`, which provides
module tasks and delegates analysis to `kodex.spec-api`. There is no root
gate or merged report; invoke the relevant modules explicitly.

`PublicApiOutsideSpec` checks explicit declaration markers, including implicit
public declarations, constructors, constructor properties and companion objects.
Non-exported containers and function/property bodies are not visited by the rule.
A non-actual exported container is reported once rather than per child member.

This is a structural guard, not type resolution. Actual/expect matching still
needs compilation. Inherited visibility, enum-entry correspondence, typealias
expansion, compiler-synthesized declarations and KDoc semantics are not verified.
Whether a spec body qualifies as a permitted short declarative inline extension
is a code-review decision, not an additional body-complexity check in this rule.
Overrides inside public actual types are conservatively checked for explicit
`actual`/`internal`/`private` markers; they are not blanket-exempted.

## Runtime and performance

- Only the custom rule is enabled, without product analysis classpaths or
  unrelated complexity/statistics processors.
- detekt's runtime and rule-build Kotlin versions are pinned together in
  `gradle/libs.versions.toml`, independently of the product compiler.
- Gradle's cacheable artifact transform gives changed plugin JARs new paths.
  This works around detekt alpha.6's path-keyed classloader cache, so rule edits
  are reloaded without restarting the shared daemon or forking every scan.
  Recheck rule reloads in the same warm daemon before removing this workaround
  on a detekt upgrade.
- Repository-relative source paths are an explicit task input: unchanged files
  moved between KMP modules can otherwise retain the same source-root-relative
  fingerprint and leave stale report locations. Recheck cross-module moves,
  restored-input cache hits and unchanged-task skips when changing source inputs.
- AST parsing is serial by default. To select parallel parsing:

  ```shell
  ./gradlew :agent-context-agents-md:reportSpecApiModule -PspecApiParallel=true
  ```

- Unchanged module tasks can be `UP-TO-DATE`; after a source edit that module's
  task may scan its complete input set. This is not per-file incremental analysis.
