import org.jetbrains.kotlin.gradle.plugin.mpp.Executable
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-cli-executable")
    alias(libs.plugins.kotlin.serialization)
    id("kodex.kmp-tests")
}

class WebPackArguments(
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val distribution: FileCollection,
    @get:Internal
    val output: File,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf(distribution.singleFile.absolutePath, output.absolutePath)
}

val webDistributionClasspath = configurations.create("webDistributionClasspath") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
dependencies {
    add("webDistributionClasspath", project(mapOf("path" to ":app-web", "configuration" to "webDistributionElements")))
}
val resourceScript = layout.settingsDirectory.file("utils/kauri/scripts/embed-frontend.mjs")
val nodeExecutable = providers.gradleProperty("kodex.nodeCommand").getOrElse("node")
val assetClang = file(providers.gradleProperty("kodex.assetClang").getOrElse("/usr/bin/clang"))
val webPack = layout.buildDirectory.file("embedded-web/frontend.pack")
val packWebResources = tasks.register<Exec>("packWebResources") {
    inputs.file(resourceScript)
    inputs.property("nodeExecutable", nodeExecutable)
    outputs.file(webPack)
    commandLine(nodeExecutable, resourceScript.asFile, "pack")
    argumentProviders.add(WebPackArguments(files(webDistributionClasspath), webPack.get().asFile))
}

kotlin {
    targets.withType<KotlinNativeTarget>().configureEach {
        val targetName = name
        val resourceObject = layout.buildDirectory.file("embedded-web/$targetName/frontend.o")
        val embedWebResources = tasks.register<Exec>("embed${targetName.replaceFirstChar { it.uppercase() }}WebResources") {
            dependsOn(packWebResources)
            inputs.file(webPack)
            inputs.file(resourceScript)
            inputs.file(assetClang)
            inputs.property("target", targetName)
            inputs.property("nodeExecutable", nodeExecutable)
            outputs.file(resourceObject)
            commandLine(nodeExecutable, resourceScript.asFile, "embed", webPack.get().asFile,
                resourceObject.get().asFile, targetName, assetClang)
        }
        binaries.withType<Executable>().configureEach {
            linkerOpts(resourceObject.get().asFile.absolutePath)
            linkTaskProvider.configure {
                dependsOn(embedWebResources)
                inputs.file(resourceObject)
            }
        }
    }
    sourceSets {
        named("specMain").dependencies {
            api(libs.clikt.core)
            api(project(":app-rpc-protocol"))
            api(project(":mcp-contract"))
            api(project(":openai-models"))
            api(project(":tool-request-user-input"))
            api(libs.kotlinx.serialization.core)
            api(libs.kotlinx.serialization.json)
        }
        commonMain.dependencies {
            implementation(libs.kotlin.logging)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.rpc.krpc.client)
            implementation(libs.kotlinx.serialization.json)
            implementation(project(":agent-storage-clean-models"))
            implementation(project(":agent-storage-core"))
            implementation(project(":app-rpc"))
            implementation(project(":tool-unified-exec"))
            implementation(project(":utils-kauri"))
            implementation(project(":utils-shell-client"))
            implementation(project(":app-backend-host"))
            implementation(project(":app-backend-migration"))
            implementation(project(":rpc-local"))
            implementation(project(":rpc-backend-control-client"))
            implementation(project(":rpc-client"))
            implementation(project(":rpc-models"))
            implementation(project(":agent-schedule-contract"))
            implementation(project(":app-backend-daemon"))
            implementation(project(":app-tui-host"))
            implementation(project(":app-web-host"))
            implementation(project(":utils-kodex-home"))
            implementation(project(":utils-logging-backend-file"))
            implementation(project(":utils-kotlinx-io-coroutines"))
            implementation(project(":utils-os-environment"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":app-backend-settings"))
            implementation(project(":openai-client-test"))
            implementation(project(":rpc-in-memory"))
            implementation(project(":rpc-server"))
        }
    }
}
