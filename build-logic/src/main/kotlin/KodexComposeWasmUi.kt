import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension

internal fun Project.configureComposeWasmUi() {
    val version = extensions.getByType(VersionCatalogsExtension::class.java)
        .named("libs").findVersion("compose-ui-wasm").get().requiredVersion
    configurations.configureEach {
        // Keep the official root and non-Wasm variants; replace only its Wasm leaf.
        resolutionStrategy.force("org.jetbrains.compose.ui:ui-wasm-js:$version")
    }
}
