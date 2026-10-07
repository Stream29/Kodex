import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    id("kodex.kmp-cli")
}

val generatedVersionDirectory =
    layout.buildDirectory.dir("generated/kodex-version/commonMain/kotlin")
val generateKodexVersion by tasks.registering(GenerateKodexVersion::class) {
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
    sourceSets {
        commonMain {
            kotlin.srcDir(generatedVersionDirectory)
            dependencies {
                api(project(":app-migration-spec"))
                implementation(project(":utils-kotlinx-io-coroutines-impl"))
                implementation(project(":agent-storage-impl-filesystem-layout"))
                implementation(project(":utils-filesystem-lease-impl"))
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kaml)
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":app-settings-impl-filesystem"))
        }
    }
}

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    dependsOn(generateKodexVersion)
}
