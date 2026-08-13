plugins {
    id("kodex.kmp-viewmodel")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":agent-runtime-contract"))
            implementation(project(":agent-session-contract"))
            implementation(project(":app-contract-agent"))
            implementation(project(":app-contract-history"))
            implementation(project(":app-shared-session-title"))
            implementation(project(":tool-request-user-input-contract"))
            implementation(project(":tool-unified-exec-impl"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":agent-storage-clean-models"))
            implementation(project(":utils-coroutines"))
        }
        commonTest.dependencies {
            implementation(project(":agent-session-in-memory"))
            implementation(project(":agent-session-test"))
            implementation(project(":utils-coroutines"))
            implementation(project(":app-viewmodel-history"))
        }
    }
}
