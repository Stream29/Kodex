plugins {
    id("kodex.kmp-viewmodel")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-contract-settings"))
            implementation(project(":app-contract-path-picker"))
            api(project(":app-shared-auth-contract"))
            implementation(project(":app-shared-session-title"))
            implementation(project(":mcp-contract"))
            implementation(project(":openai-client-contract"))
            implementation(project(":utils-coroutines"))
            implementation(project(":utils-logging"))
            implementation(libs.kotlin.logging)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
