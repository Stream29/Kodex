import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    id("kodex.kmp-host")
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
                api(project(":app-migration-contract"))
                implementation(project(":agent-storage-filesystem-layout"))
                implementation(project(":utils-filesystem-lease-impl"))
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    dependsOn(generateKodexVersion)
}
