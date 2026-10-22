plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-viewmodel")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-session-contract"))
            api(project(":app-component-agent-contract"))
            api(project(":app-component-composer-viewmodel"))
            api(project(":app-component-runtime-configuration-viewmodel"))
            api(project(":app-rpc"))
            api(project(":openai-models"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
        }
        commonTest.dependencies {
            implementation(project(":app-test-support-rpc"))
            implementation(project(":agent-session-in-memory"))
            implementation(project(":agent-session-test"))
            implementation(project(":app-component-history-viewmodel"))
            implementation(project(":app-session"))
            implementation(project(":openai-client-test"))
            implementation(project(":utils-coroutines"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
