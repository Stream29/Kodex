plugins {
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
                api(project(":tool-core"))
                api(project(":utils-kotlinx-io-serialization"))
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.io.core)
                api(libs.kotlinx.serialization.core)
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
            implementation(project(":utils-logging"))
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
        jvmTest.dependencies {
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.core)
        }
    }
}

kotlin {
    sourceSets {
        // The process-owned transport is implementation; its port is external.
        commonMain.dependencies {
            api(libs.mcp.kotlin.sdk.client)
            api(project(":utils-process-client"))
            implementation(libs.kotlinx.io.core)
        }
        commonTest.dependencies {
            implementation(project(":utils-process-client-system"))
        }
        jvmTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

kotlin {
    sourceSets {
        // HTTP/SSE construction and authorization interception are implementation.
        commonMain.dependencies {
            api(libs.ktor.client.core)
            api(libs.mcp.kotlin.sdk.client)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.sse)
        }
        jvmMain.dependencies {
            implementation(libs.bundles.ktor.client.jvm.engines)
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
