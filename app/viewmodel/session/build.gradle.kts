plugins {
    id("kodex.kmp-viewmodel")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":agent-session-contract"))
            implementation(project(":app-contract-session"))
            implementation(project(":app-contract-session-catalog"))
            implementation(project(":agent-state-contract"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":agent-storage-contract"))
            implementation(project(":agent-storage-contract-ext"))
            implementation(project(":utils-coroutines"))
        }
        commonTest.dependencies {
            implementation(project(":agent-storage-contract-ext"))
            implementation(project(":agent-session-filesystem"))
            implementation(project(":app-viewmodel-agent"))
            implementation(project(":app-viewmodel-history"))
            implementation(project(":agent-session-in-memory"))
            implementation(project(":agent-session-test"))
            implementation(project(":app-shared-session-title"))
            implementation(project(":openai-client-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
