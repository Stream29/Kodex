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
            api(project(":app-rpc-protocol"))
            api(project(":rpc-models"))
            api(project(":rpc-connection"))
            api(project(":openai-models"))
            api(project(":app-component-agent-contract"))
            api(project(":app-component-session-contract"))
            api(project(":app-component-composer-viewmodel"))
            api(project(":app-component-runtime-configuration-viewmodel"))
            api(project(":app-component-path-picker-viewmodel"))
            api(project(":app-component-session-catalog-viewmodel"))
            api(project(":app-component-settings-viewmodel"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        nativeTest.dependencies {
            implementation(project(":rpc-server"))
            implementation(project(":app-backend-migration"))
            implementation(project(":app-backend-settings"))
            implementation(project(":openai-client"))
            implementation(project(":app-rpc-test-support"))
            implementation(project(":app-component-history-viewmodel"))
            implementation(project(":agent-storage-core"))
            implementation(project(":agent-session-test"))
            implementation(project(":openai-client-test"))
            implementation(project(":utils-coroutines"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
