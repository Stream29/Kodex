import org.jetbrains.kotlin.gradle.plugin.mpp.Executable
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-cli-executable")
    id("kodex.kmp-tests")
}

val webDistribution = project(":app-web").layout.buildDirectory.dir("dist/wasmJs/productionExecutable")
val resourceScript = rootProject.layout.projectDirectory.file("utils/kauri/scripts/embed-frontend.mjs")
val nodeExecutable = providers.gradleProperty("kodex.nodeCommand").getOrElse("node")
val assetClang = file(providers.gradleProperty("kodex.assetClang").getOrElse("/usr/bin/clang"))
val webPack = layout.buildDirectory.file("embedded-web/frontend.pack")
val packWebResources = tasks.register<Exec>("packWebResources") {
    dependsOn(":app-web:wasmJsBrowserDistribution")
    inputs.dir(webDistribution)
    inputs.file(resourceScript)
    inputs.property("nodeExecutable", nodeExecutable)
    outputs.file(webPack)
    commandLine(nodeExecutable, resourceScript.asFile, "pack", webDistribution.get().asFile, webPack.get().asFile)
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
        commonMain.dependencies {
            implementation(libs.clikt.core)
            implementation(project(":app-rpc-protocol"))
            implementation(libs.kotlin.logging)
            implementation(libs.kotlinx.coroutines.core)
        }
        mosaicMain.dependencies {
            implementation(project(":utils-kauri"))
            implementation(project(":app-backend-host"))
            implementation(project(":rpc-local"))
            implementation(project(":rpc-backend-control-client"))
            implementation(project(":app-backend-daemon"))
            implementation(libs.kotlinx.serialization.json)
            implementation(project(":app-tui-host"))
            implementation(project(":app-web-host"))
            implementation(project(":app-rpc-application"))
            implementation(project(":app-rpc-system"))
            implementation(project(":utils-kodex-home"))
            implementation(project(":utils-kotlinx-io-coroutines-system"))
            implementation(project(":utils-os-environment"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.core)
        }
    }
}
