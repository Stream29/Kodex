plugins {
    id("kodex.kmp-viewmodel")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlin.logging)
            implementation(project(":agent-state-contract"))
            implementation(project(":agent-storage-contract"))
            implementation(project(":app-contract-history"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":utils-coroutines-spec"))
            implementation(project(":utils-rpc-exception-spec"))
        }
        commonTest.dependencies {
            implementation(project(":openai-spec-json-codec"))
            implementation(project(":agent-session-in-memory"))
            implementation(project(":agent-session-test"))
            implementation(project(":agent-storage-contract-ext"))
            implementation(project(":agent-storage-in-memory"))
        }
    }
}
