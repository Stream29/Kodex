import org.gradle.api.tasks.testing.AbstractTestTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-cli-host-layer")
    id("kodex.ktor-native-network-override")
    id("kodex.kmp-tests")
}

val generatedVersionDirectory =
    layout.buildDirectory.dir("generated/kodex-version/cliHostMain/kotlin")
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
        named("cliHostMain") {
            kotlin.srcDir(files(generatedVersionDirectory).builtBy(generateKodexVersion))
            dependencies {
                implementation(project(":agent-storage-filesystem-layout"))
                implementation(project(":utils-filesystem-lease"))
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kaml)
            }
        }
        named("cliHostTest") {
            // Preserve this published test verbatim at its historical path. Its retired
            // combined-store API is not a current product dependency. The complete
            // current-store regression lives in MigrateToV047CurrentStoresTest.kt.
            kotlin.exclude("**/v0_4_7/MigrateToV0_4_7Test.kt")
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
                implementation(project(":app-settings-filesystem"))
                implementation(project(":agent-storage-clean-models"))
                implementation(project(":agent-storage-core"))
                implementation(project(":openai-json-codec"))
            }
        }
    }
}

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    dependsOn(generateKodexVersion)
}

tasks.withType<AbstractTestTask>().configureEach {
    // The published v0_4_3 suite remains byte-for-byte frozen. Only this
    // coordinator assertion requires the removed no-migration tail write.
    // Its migration/skill coverage is retained in KodexHomeMigrationCheckpointTest.
    filter.excludeTestsMatching(
        "*migrateToV0_4_3Test*does not activate the future skill at application version*",
    )
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":utils-kotlinx-io-coroutines"))
            api(project(":utils-kotlinx-io-coroutines-system"))
        }
    }
}
