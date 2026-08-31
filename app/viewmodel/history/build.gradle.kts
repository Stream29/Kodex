plugins {
    id("kodex.kmp-viewmodel")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":agent-state-contract"))
            implementation(project(":agent-storage-contract"))
            implementation(project(":app-contract-history"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":utils-coroutines"))
        }
        commonTest.dependencies {
            implementation(project(":agent-session-in-memory"))
            implementation(project(":agent-session-test"))
            implementation(project(":agent-storage-contract-ext"))
            implementation(project(":agent-storage-in-memory"))
        }
    }
}
