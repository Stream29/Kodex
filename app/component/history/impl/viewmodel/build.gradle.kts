plugins {
    id("kodex.kmp-viewmodel")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlin.logging)
            implementation(project(":agent-state-spec-contract"))
            implementation(project(":agent-storage-spec-contract"))
            implementation(project(":app-component-history-spec"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":utils-coroutines-spec"))
            implementation(project(":utils-rpc-exception-spec"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":openai-spec-json-codec"))
            implementation(project(":agent-session-impl-in-memory"))
            implementation(project(":agent-session-test"))
            implementation(project(":agent-storage-spec-contract-ext"))
            implementation(project(":agent-storage-impl-in-memory"))
        }
    }
}
