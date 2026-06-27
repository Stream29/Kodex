import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("codexlite.kmp-host")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    targets.withType<KotlinNativeTarget>().configureEach {
        if (name == "mingwX64") {
            binaries.configureEach {
                linkerOpts("-lole32")
            }
        }
    }

    js {
        nodejs {
            testTask {
                useMocha {
                    timeout = "360s"
                }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
            api(project(":openai:client-contract"))
            api(project(":openai:models"))
            api(project(":tool:tool-builder"))
            api(project(":tool:contract"))
            api(project(":utils:images"))
            api(project(":utils:images-codec"))
            api(project(":utils:kotlinx-io-coroutines"))
            implementation(libs.kotlinx.schema.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":openai:client"))
            implementation(project(":openai:client-test"))
            implementation(project(":openai:codex-cli-storage"))
            implementation(project(":openai:json-codec"))
            implementation(project(":utils:os-environment"))
        }
    }
}
