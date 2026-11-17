plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-frontend")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":agent-context-settings"))
            api(project(":app-component-path-picker-viewmodel"))
            api(project(":app-component-session-contract"))
            api(project(":app-rpc-protocol"))
            api(project(":mcp-contract"))
            api(project(":openai-account-usage"))
            api(project(":openai-models"))
            api(project(":rpc-models"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
        }
        commonMain.dependencies {
            implementation(project(":utils-coroutines"))
            implementation(libs.kotlin.logging)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.serialization.json)
        }
        nativeTest.dependencies {
            implementation(project(":app-backend-settings"))
            implementation(project(":app-rpc-test-support"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
