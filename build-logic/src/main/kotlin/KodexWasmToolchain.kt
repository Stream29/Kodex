import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsPlugin

internal fun Project.configureWasmNodeCommand() {
    plugins.withType(WasmNodeJsPlugin::class.java) {
        providers.gradleProperty("kodex.nodeCommand").orNull?.let { executable ->
            extensions.getByType(WasmNodeJsEnvSpec::class.java).apply {
                download.set(false)
                command.set(executable)
            }
        }
    }
}
