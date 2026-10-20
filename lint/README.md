# Spec API lint

Run from the Kodex root with the project's JDK:

```shell
./gradlew checkSpecApi
./gradlew reportSpecApi
./gradlew :lint:test
```

- `checkSpecApi` is strict and fails on findings. Existing APIs are not baselined.
- `reportSpecApi` writes the same findings without failing; success does not mean
  that the API boundary is clean.
- Reports are in `build/reports/spec-api/{detekt,reportSpecApi}.sarif`.
- These are independent entrypoints; this change does not migrate APIs or add CI.

## Scope

The root task collects real KMP main-compilation source sets, excludes `specMain`,
and scans each implementation file once. Intermediate/platform layers and
consumable main fixtures are included. Source filters and generated-source
producer dependencies are preserved; tests and external fork builds are excluded.

`PublicApiOutsideSpec` checks explicit declaration markers, including implicit
public declarations, constructors, constructor properties and companion objects.
Non-exported containers and function/property bodies are not visited by the rule.
A non-actual exported container is reported once rather than per child member.

This is a structural guard, not type resolution. Actual/expect matching still
needs compilation. Inherited visibility, enum-entry correspondence, typealias
expansion, compiler-synthesized declarations and KDoc semantics are not verified.
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
- AST parsing is serial by default. To compare parallel parsing without forcing
  producer tasks to rebuild:

  ```shell
  ./gradlew reportSpecApi --rerun -PspecApiParallel=true
  ```

- Unchanged successful tasks can be `UP-TO-DATE`; after a source edit the task
  may scan the complete input set. This is not per-file incremental analysis.
