plugins {
    id("kodex.kmp-cli")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlinx.rpc)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":rpc-spec-models"))
            api(project(":app-contract-settings"))
            api(project(":app-contract-session-catalog"))
            api(project(":mcp-spec-contract"))
            api(project(":agent-storage-clean-models"))
            api(project(":agent-storage-contract"))
            api(project(":openai-spec-models"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.rpc.core)
            api(libs.kotlinx.serialization.core)
        }
        commonTest.dependencies {
            implementation(project(":utils-rpc-exception-spec"))
            implementation(libs.kotlinx.rpc.krpc.client)
            implementation(libs.kotlinx.rpc.krpc.server)
            implementation(libs.kotlinx.rpc.krpc.serialization.json)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
