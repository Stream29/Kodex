plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.ktor-native-network-override")
    alias(libs.plugins.kotlin.serialization)
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":mcp-contract"))
            api(project(":utils-process-client"))
            api(libs.mcp.kotlin.sdk.client)
            api(libs.ktor.client.core)
            api(libs.kotlinx.coroutines.core)
        }
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            implementation(libs.mcp.kotlin.sdk.client)
            implementation(project(":utils-coroutines"))
            implementation(project(":utils-logging-backend-scope"))
            implementation(project(":utils-process-client"))
            implementation(project(":utils-read-write-mutex"))
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.sse)
            implementation(libs.ktor.utils)
            implementation(libs.kotlin.logging)
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.schema.json)
            implementation(libs.kotlinx.serialization.json)
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
            implementation(project(":openai-json-codec"))
            implementation(project(":utils-process-client"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.mock)
        }
    }
}
