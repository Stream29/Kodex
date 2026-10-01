plugins {
    id("kodex.kmp-viewmodel")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":agent-state-contract"))
            implementation(project(":agent-storage-contract"))
            implementation(project(":app-contract-agent"))
            implementation(project(":app-contract-history"))
            implementation(project(":tool-spec-request-user-input"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":agent-storage-clean-models"))
            implementation(project(":utils-coroutines-spec"))
            implementation(project(":utils-rpc-exception-spec"))
        }
        commonTest.dependencies {
            implementation(project(":agent-session-in-memory"))
            implementation(project(":agent-session-test"))
            implementation(project(":agent-storage-in-memory"))
            implementation(project(":utils-coroutines-spec"))
            implementation(project(":app-viewmodel-history"))
        }
    }
}
