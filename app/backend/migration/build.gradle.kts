@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.plugin.KotlinHierarchyTemplate
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

val generatedVersionDirectory =
    layout.buildDirectory.dir("generated/kodex-version/cliHostMain/kotlin")
val generateKodexVersion = tasks.register<GenerateKodexVersion>("generateKodexVersion") {
    applicationVersion.set(project.version.toString())
    outputFile.set(
        generatedVersionDirectory.map { directory ->
            directory.file(
                "io/github/stream29/kodex/app/migration/" +
                    "GeneratedKodexApplicationVersion.kt",
            )
        },
    )
}

kotlin {
    applyHierarchyTemplate(KotlinHierarchyTemplate.default) {
        common {
            group("cliHost") {
                group("desktopNative") {
                    withLinuxX64()
                    withLinuxArm64()
                    withMacosArm64()
                    withMingwX64()
                }
            }
        }
    }

    sourceSets {
        named("specMain").dependencies {
            api(project(":utils-kotlinx-io-coroutines"))
        }
        named("cliHostMain") {
            kotlin.srcDir(files(generatedVersionDirectory).builtBy(generateKodexVersion))
            dependencies {
                implementation(project(":agent-storage-sqlite"))
                implementation(project(":utils-sqlite"))
                implementation(project(":utils-filesystem-lease"))
                implementation(libs.kotlinx.serialization.json)
            }
        }
    }
}

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    dependsOn(generateKodexVersion)
}
