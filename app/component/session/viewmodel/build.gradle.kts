plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-frontend")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-component-session-contract"))
            api(project(":openai-models"))
            api(libs.kotlinx.coroutines.core)
        }
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        nativeTest.dependencies {
            implementation(project(":rpc-server"))
            implementation(project(":rpc-in-memory"))
            implementation(project(":utils-kotlinx-io-coroutines-system"))
            implementation(project(":app-component-session-catalog-viewmodel"))
            implementation(project(":app-rpc-test-support"))
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
