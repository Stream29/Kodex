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
            api(project(":rpc-models"))
            api(project(":rpc-connection"))
            api(project(":app-component-working-directory-viewmodel"))
            api(project(":app-component-session-rename-viewmodel"))
            api(project(":app-component-session-delete-viewmodel"))
            api(project(":app-component-openai-login-viewmodel"))
            api(project(":app-component-agent-contract"))
            api(project(":tool-multi-agent"))
            api(project(":app-component-path-picker-viewmodel"))
            api(project(":app-component-session-catalog-viewmodel"))
            api(project(":app-component-session-contract"))
            api(project(":app-component-new-session-viewmodel"))
            api(project(":app-component-settings-viewmodel"))
            api(project(":app-component-application-preferences-viewmodel"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
        }
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
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
            implementation(project(":agent-storage-core"))
            implementation(project(":agent-session-in-memory"))
            implementation(project(":agent-session-test"))
            implementation(project(":openai-client-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
