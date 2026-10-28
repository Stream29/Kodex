plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-cli-host-layer")
    id("kodex.ktor-native-network-override")
    alias(libs.plugins.kotlin.serialization)
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(project(":mcp-contract"))
                api(project(":utils-process-client"))
                api(libs.mcp.kotlin.sdk.client)
                api(libs.ktor.client.core)
                api(libs.kotlinx.coroutines.core)
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

kotlin {
    sourceSets {
        named("cliHostMain").dependencies {
            api(libs.kotlinx.coroutines.core)
            implementation(libs.mcp.kotlin.sdk.client)
            implementation(project(":utils-coroutines"))
            implementation(project(":utils-logging-backend-scope"))
            implementation(project(":utils-process-client-system"))
            implementation(project(":utils-read-write-mutex"))
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.utils)
            implementation(libs.kotlin.logging)
            implementation(libs.kotlinx.schema.json)
            implementation(libs.kotlinx.serialization.json)
        }
        named("cliHostTest").dependencies {
            implementation(project(":agent-runtime-decorator-tool"))
            implementation(project(":agent-state-core"))
            implementation(project(":agent-state-test"))
            implementation(project(":tool-plan"))
            implementation(project(":agent-storage-core"))
            implementation(project(":openai-client-test"))
            implementation(project(":openai-json-codec"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

kotlin {
    sourceSets {
        // The process-owned transport is implementation; its port is external.
        commonMain.dependencies {
            implementation(libs.kotlinx.io.core)
        }
        commonTest.dependencies {
            implementation(project(":utils-process-client-system"))
        }
    }
}

kotlin {
    sourceSets {
        // HTTP/SSE construction and authorization interception are implementation.
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.sse)
        }
        linuxMain.dependencies {
            implementation(libs.bundles.ktor.client.linux.engines)
        }
        macosArm64Main.dependencies {
            implementation(libs.bundles.ktor.client.macos.engines)
        }
        mingwX64Main.dependencies {
            implementation(libs.bundles.ktor.client.mingw.engines)
        }
        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
        }
    }
}
