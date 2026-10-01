plugins {
    id("kodex.kmp-viewmodel")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":agent-state-contract"))
            implementation(project(":agent-storage-spec-contract"))
            implementation(project(":app-contract-agent"))
            implementation(project(":app-contract-history"))
            implementation(project(":tool-spec-request-user-input"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":agent-storage-spec-clean-models"))
            implementation(project(":utils-coroutines-spec"))
            implementation(project(":utils-rpc-exception-spec"))
        }
        commonTest.dependencies {
            implementation(project(":agent-session-impl-in-memory"))
            implementation(project(":agent-session-test"))
            implementation(project(":agent-storage-impl-in-memory"))
            implementation(project(":utils-coroutines-spec"))
            implementation(project(":app-viewmodel-history"))
        }
    }
}
