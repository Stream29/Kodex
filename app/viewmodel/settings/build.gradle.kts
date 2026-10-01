plugins {
    id("kodex.kmp-viewmodel")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-contract-settings"))
            implementation(project(":app-component-path-picker-spec"))
            implementation(project(":app-shared-session-title"))
            implementation(project(":mcp-spec-contract"))
            implementation(project(":openai-spec-client"))
            implementation(project(":utils-coroutines-spec"))
            implementation(project(":utils-logging-impl"))
            implementation(project(":utils-os-environment-impl"))
            implementation(libs.kotlin.logging)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":app-test-support-rpc"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
