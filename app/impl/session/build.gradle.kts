plugins {
    id("kodex.kmp-viewmodel")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":app-impl-rpc"))
            implementation(project(":app-spec-session"))
            implementation(project(":app-component-session-catalog-spec"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":utils-coroutines-spec"))
        }
        commonTest.dependencies {
            implementation(project(":rpc-impl-server"))
            implementation(project(":rpc-impl-in-memory"))
            implementation(project(":utils-kotlinx-io-coroutines-impl"))
            implementation(project(":app-component-session-catalog-impl-viewmodel"))
            implementation(project(":app-test-support-rpc"))
            implementation(project(":agent-storage-spec-contract-ext"))
            implementation(project(":agent-session-impl-filesystem"))
            implementation(project(":app-component-history-impl-viewmodel"))
            implementation(project(":agent-session-impl-in-memory"))
            implementation(project(":agent-session-test"))
            implementation(project(":openai-impl-client-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
