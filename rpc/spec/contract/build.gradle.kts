plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-cli")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlinx.rpc)
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(project(":rpc-spec-models"))
                api(project(":app-component-authentication-settings-impl-viewmodel"))
                api(project(":app-component-account-usage-impl-viewmodel"))
                api(project(":app-component-session-catalog-impl-viewmodel"))
                api(project(":mcp-spec-contract"))
                api(project(":agent-storage-spec-clean-models"))
                api(project(":agent-storage-core"))
                api(project(":openai-spec-models"))
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.rpc.core)
                api(libs.kotlinx.serialization.core)
            }
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
