plugins {
    id("kodex.kmp-viewmodel")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":app-viewmodel-rpc"))
            implementation(project(":app-contract-session"))
            implementation(project(":app-component-session-catalog-spec"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":utils-coroutines-spec"))
        }
        commonTest.dependencies {
            implementation(project(":app-test-support-rpc"))
            implementation(project(":agent-storage-spec-contract-ext"))
            implementation(project(":agent-session-impl-filesystem"))
            implementation(project(":app-viewmodel-agent"))
            implementation(project(":app-viewmodel-history"))
            implementation(project(":agent-session-impl-in-memory"))
            implementation(project(":agent-session-test"))
            implementation(project(":app-shared-session-title"))
            implementation(project(":openai-impl-client-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
