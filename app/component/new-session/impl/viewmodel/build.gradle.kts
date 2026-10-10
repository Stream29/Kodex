plugins {
    id("kodex.kmp-viewmodel")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":app-impl-rpc"))
            implementation(project(":app-component-agent-spec"))
            api(project(":app-component-new-session-spec"))
            implementation(project(":openai-spec-models"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.core)
        }
        commonTest.dependencies {
            implementation(project(":app-test-support-rpc"))
            implementation(project(":agent-session-impl-in-memory"))
            implementation(project(":agent-session-test"))
            implementation(project(":app-component-history-impl-viewmodel"))
            implementation(project(":app-impl-session"))
            implementation(project(":openai-impl-client-test"))
            implementation(project(":utils-coroutines-spec"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
