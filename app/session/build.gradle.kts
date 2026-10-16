plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-viewmodel")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":app-rpc"))
            implementation(project(":app-session-contract"))
            implementation(project(":app-component-session-catalog-viewmodel"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":utils-coroutines"))
        }
        commonTest.dependencies {
            implementation(project(":rpc-server"))
            implementation(project(":rpc-in-memory"))
            implementation(project(":utils-kotlinx-io-coroutines-system"))
            implementation(project(":app-component-session-catalog-viewmodel"))
            implementation(project(":app-test-support-rpc"))
            implementation(project(":agent-storage-core"))
            implementation(project(":agent-session-filesystem"))
            implementation(project(":app-component-history-viewmodel"))
            implementation(project(":agent-session-in-memory"))
            implementation(project(":agent-session-test"))
            implementation(project(":openai-client-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
