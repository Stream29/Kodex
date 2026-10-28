@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import java.security.MessageDigest
import java.util.HexFormat
import javax.inject.Inject
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.targets.js.dsl.KotlinWasmTargetDsl

plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-web-executable")
    id("kodex.kmp-tests")
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    wasmJs { browser { commonWebpackConfig { outputFileName = "kodex-web.js" } } }
    sourceSets {
        commonMain.dependencies {
            implementation(project(":app-component-application-viewmodel"))
            implementation(project(":app-rpc-application"))
            implementation(project(":rpc-browser-client"))
            implementation(project(":app-web-renderer"))
            implementation(project(":app-component-widgets"))
            implementation(libs.kotlinx.serialization.json)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }
    }
}

val webVersion = tasks.register("webVersion") {
    val directory = layout.buildDirectory.dir("generated/web-version")
    inputs.property("applicationVersion", project.version.toString())
    outputs.dir(directory)
    val applicationVersion = project.version.toString()
    doLast {
        directory.get().asFile.apply { mkdirs() }.resolve("version.txt").writeText("$applicationVersion\n")
    }
}
kotlin.sourceSets.named("wasmJsMain") { resources.srcDir(webVersion) }

val composeUiNotices = configurations.create("composeUiNotices") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
dependencies {
    composeUiNotices("org.jetbrains.compose.ui:ui-wasm-js:${libs.versions.compose.ui.wasm.get()}:sources@jar")
}

abstract class SyncComposeNotices : DefaultTask() {
    @get:Classpath
    abstract val sourceArchives: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val destination: DirectoryProperty

    @get:Inject
    abstract val archives: ArchiveOperations

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun syncNotices() {
        files.sync {
            from(sourceArchives.files.map { archives.zipTree(it) }) {
                include(
                    "META-INF/androidx/compose/ui/ui/LICENSE.txt",
                    "META-INF/androidx/compose/ui/ui/KODEX-NOTICE.md",
                )
                eachFile {
                    path = "third-party/compose-ui/" + if (name == "KODEX-NOTICE.md") "NOTICE.md" else name
                }
                includeEmptyDirs = false
            }
            into(destination)
        }
    }
}

val webCompatibilityNotices = tasks.register<SyncComposeNotices>("webCompatibilityNotices") {
    sourceArchives.from(composeUiNotices)
    destination.set(layout.buildDirectory.dir("generated/web-third-party"))
}
kotlin.sourceSets.named("wasmJsMain") { resources.srcDir(webCompatibilityNotices) }

val webKauriNotices = tasks.register<Sync>("webKauriNotices") {
    into(layout.buildDirectory.dir("generated/web-kauri-notices"))
    into("third-party/kauri") {
        from(layout.settingsDirectory.file("utils/kauri/LICENSE-WebView2-SDK.txt"))
        from(layout.settingsDirectory.file("utils/kauri/NOTICE.md"))
    }
}
kotlin.sourceSets.named("wasmJsMain") { resources.srcDir(webKauriNotices) }

val webDistribution = (kotlin.targets.getByName("wasmJs") as KotlinWasmTargetDsl)
    .binaries.single { it.name == "productionExecutable" }.distribution.outputDirectory
configurations.create("webDistributionElements") {
    isCanBeConsumed = true
    isCanBeResolved = false
    outgoing.artifact(webDistribution) {
        builtBy("wasmJsBrowserDistribution")
    }
}

val webChecksums = tasks.register("webChecksums") {
    dependsOn("wasmJsBrowserDistribution")
    val distribution = layout.buildDirectory.dir("dist/wasmJs/productionExecutable")
    val manifest = layout.buildDirectory.file("reports/web/SHA256SUMS")
    inputs.dir(distribution)
    outputs.file(manifest)
    doLast {
        val root = distribution.get().asFile
        val digest = MessageDigest.getInstance("SHA-256")
        val lines = root.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.map {
            val hash = HexFormat.of().formatHex(digest.digest(it.readBytes()))
            "$hash  ${it.relativeTo(root).invariantSeparatorsPath}\n"
        }.joinToString("")
        manifest.get().asFile.apply { parentFile.mkdirs(); writeText(lines) }
    }
}

val webArchive = tasks.register<Zip>("webArchive") {
    dependsOn(webChecksums)
    archiveBaseName.set("kodex-web")
    archiveVersion.set(project.version.toString())
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(layout.buildDirectory.dir("dist/wasmJs/productionExecutable"))
    from(webChecksums)
}
