@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import java.security.MessageDigest
import java.util.HexFormat

plugins {
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
val webCompatibilityNotices = tasks.register<Sync>("webCompatibilityNotices") {
    from(composeUiNotices.elements.map { sources -> sources.map { zipTree(it.asFile) } }) {
        include(
            "META-INF/androidx/compose/ui/ui/LICENSE.txt",
            "META-INF/androidx/compose/ui/ui/KODEX-NOTICE.md",
        )
        eachFile {
            path = "third-party/compose-ui/" + if (name == "KODEX-NOTICE.md") "NOTICE.md" else name
        }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("generated/web-third-party"))
}
kotlin.sourceSets.named("wasmJsMain") { resources.srcDir(webCompatibilityNotices) }

val webKauriNotices = tasks.register<Sync>("webKauriNotices") {
    into(layout.buildDirectory.dir("generated/web-kauri-notices"))
    into("third-party/kauri") {
        from(rootProject.layout.projectDirectory.file("utils/kauri/LICENSE-WebView2-SDK.txt"))
        from(rootProject.layout.projectDirectory.file("utils/kauri/NOTICE.md"))
    }
}
kotlin.sourceSets.named("wasmJsMain") { resources.srcDir(webKauriNotices) }

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
