plugins {
    id("codexlite.kmp-host")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
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
            api(libs.ktor.client.core)
            api(project(":auth"))
            api(project(":tool:contract"))
            api(project(":utils:images"))
            api(project(":utils:images-codec"))
            api(project(":utils:kotlinx-io-coroutines"))
            implementation(project(":llm-provider:models"))
            implementation(libs.kotlinx.schema.json)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":codex-compatibility"))
        }
        jvmTest.dependencies {
            implementation(libs.bundles.ktor.client.jvm.engines)
        }
        linuxTest.dependencies {
            implementation(libs.bundles.ktor.client.linux.engines)
        }
    }
}
