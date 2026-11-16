plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-frontend")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlinx.rpc)
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-rpc-protocol"))
            api(project(":rpc-models"))
            api(project(":agent-schedule-contract"))
            api(project(":mcp-contract"))
            api(project(":agent-storage-clean-models"))
            api(project(":agent-storage-core"))
            api(project(":openai-models"))
            api(project(":tool-request-user-input"))
            api(project(":utils-kotlinx-io-serialization"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.rpc.core)
            api(libs.kotlinx.serialization.core)
            api(libs.kotlinx.serialization.json)
        }
        commonMain.dependencies {
            api(project(":utils-rpc-exception"))
            implementation(libs.cache4k)
        }
        commonTest.dependencies {
            implementation(project(":rpc-in-memory"))
            implementation(project(":utils-rpc-exception"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.rpc.krpc.client)
            implementation(libs.kotlinx.rpc.krpc.server)
            implementation(libs.kotlinx.rpc.krpc.serialization.json)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
