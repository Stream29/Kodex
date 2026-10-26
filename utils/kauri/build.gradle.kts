import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable

plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
}

val resourceScript = layout.projectDirectory.file("scripts/embed-frontend.mjs")
val nodeExecutable = providers.gradleProperty("kodex.nodeCommand").getOrElse("node")
val assetClang = file(providers.gradleProperty("kodex.assetClang").getOrElse("/usr/bin/clang"))
val fixturePack = layout.buildDirectory.file("embedded-test/resources.pack")
val packTestResources = tasks.register<Exec>("packTestResources") {
    val resources = layout.projectDirectory.dir("src/nativeTest/resources/embedded")
    inputs.dir(resources)
    inputs.file(resourceScript)
    inputs.property("nodeExecutable", nodeExecutable)
    outputs.file(fixturePack)
    commandLine(nodeExecutable, resourceScript.asFile, "pack", resources.asFile, fixturePack.get().asFile)
}

kotlin {
    targets.withType<KotlinNativeTarget>().configureEach {
        compilations["main"].cinterops.create("embeddedAssets") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/embedded-assets.def"))
            compilerOpts("-I${project.file("src/nativeInterop/cinterop").absolutePath}")
            tasks.named(interopProcessingTaskName) {
                inputs.file(layout.projectDirectory.file("src/nativeInterop/cinterop/kauri_assets.h"))
            }
        }
        when (name) {
            "macosArm64" -> binaries.all { linkerOpts("-framework", "AppKit", "-framework", "WebKit") }
            "mingwX64" -> {
                compilations["main"].cinterops.create("windowsWebView2") {
                    definitionFile.set(project.file("src/nativeInterop/windows/webview2.def"))
                    compilerOpts("-I${project.file("src/nativeInterop/windows").absolutePath}")
                    tasks.named(interopProcessingTaskName) {
                        inputs.files(
                            layout.projectDirectory.file("src/nativeInterop/windows/WebView2.h"),
                            layout.projectDirectory.file("src/nativeInterop/windows/EventToken.h"),
                        )
                    }
                }
                binaries.all { linkerOpts("-lole32", "-luser32") }
            }
            "linuxX64", "linuxArm64" -> compilations["main"].cinterops.create("linuxGtk") {
                definitionFile.set(project.file("src/nativeInterop/linux/kauri_gtk.def"))
                compilerOpts("-I${project.file("src/nativeInterop/linux").absolutePath}")
                tasks.named(interopProcessingTaskName) {
                    inputs.file(layout.projectDirectory.file("src/nativeInterop/linux/kauri_gtk.h"))
                }
            }
        }
        val targetName = name
        val fixtureObject = layout.buildDirectory.file("embedded-test/$targetName/resources.o")
        val embedTestResources = tasks.register<Exec>("embed${targetName.replaceFirstChar { it.uppercase() }}TestResources") {
            dependsOn(packTestResources)
            inputs.file(fixturePack)
            inputs.file(resourceScript)
            inputs.file(assetClang)
            inputs.property("target", targetName)
            inputs.property("nodeExecutable", nodeExecutable)
            outputs.file(fixtureObject)
            commandLine(nodeExecutable, resourceScript.asFile, "embed", fixturePack.get().asFile,
                fixtureObject.get().asFile, targetName, assetClang)
        }
        binaries.withType<TestExecutable>().configureEach {
            linkerOpts(fixtureObject.get().asFile.absolutePath)
            linkTaskProvider.configure {
                dependsOn(embedTestResources)
                inputs.file(fixtureObject)
            }
        }
    }
    sourceSets {
        specMain.dependencies { api(libs.kotlinx.io.core) }
        commonTest.dependencies { implementation(kotlin("test")) }
        val posixTest by creating { dependsOn(getByName("nativeTest")) }
        getByName("linuxX64Test").dependsOn(posixTest)
        getByName("linuxArm64Test").dependsOn(posixTest)
        getByName("macosArm64Test").dependsOn(posixTest)
    }
}
