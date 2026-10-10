plugins {
    id("kodex.kmp-cli")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlinx.rpc)
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":rpc-spec-models"))
            api(project(":app-component-authentication-settings-spec"))
            api(project(":app-component-account-usage-spec"))
            api(project(":app-component-session-catalog-spec"))
            api(project(":mcp-spec-contract"))
            api(project(":agent-storage-spec-clean-models"))
            api(project(":agent-storage-spec-contract"))
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
