plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-frontend")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-rpc-protocol"))
            api(project(":app-component-session-contract"))
            api(project(":app-component-agent-contract"))
            api(project(":app-component-composer-viewmodel"))
            api(project(":app-component-runtime-configuration-viewmodel"))
            api(project(":openai-models"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
        }
        nativeTest.dependencies {
            implementation(project(":app-rpc-test-support"))
            implementation(project(":agent-session-in-memory"))
            implementation(project(":agent-session-test"))
            implementation(project(":app-component-history-viewmodel"))
            implementation(project(":app-component-session-viewmodel"))
            implementation(project(":openai-client-test"))
            implementation(project(":utils-coroutines"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
