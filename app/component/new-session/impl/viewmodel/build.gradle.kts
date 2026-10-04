plugins {
    id("kodex.kmp-viewmodel")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":app-viewmodel-rpc"))
            implementation(project(":app-contract-agent"))
            api(project(":app-component-new-session-spec"))
            implementation(project(":openai-spec-models"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.core)
        }
        commonTest.dependencies {
            implementation(project(":app-test-support-rpc"))
            implementation(project(":agent-session-impl-in-memory"))
            implementation(project(":agent-session-test"))
            implementation(project(":app-viewmodel-agent"))
            implementation(project(":app-component-history-impl-viewmodel"))
            implementation(project(":app-viewmodel-session"))
            implementation(project(":app-shared-session-title"))
            implementation(project(":openai-impl-client-test"))
            implementation(project(":utils-coroutines-spec"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
