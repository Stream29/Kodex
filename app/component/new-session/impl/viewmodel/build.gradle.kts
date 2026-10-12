plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-viewmodel")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-spec-session"))
            api(project(":app-component-composer-impl-viewmodel"))
            api(project(":app-component-runtime-configuration-impl-viewmodel"))
        }
        commonMain.dependencies {
            implementation(project(":app-impl-rpc"))
            implementation(project(":app-component-agent-spec"))
            implementation(project(":openai-spec-models"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.core)
        }
        commonTest.dependencies {
            implementation(project(":app-test-support-rpc"))
            implementation(project(":agent-session-impl-in-memory"))
            implementation(project(":agent-session-test"))
            implementation(project(":app-component-history-impl-viewmodel"))
            implementation(project(":app-impl-session"))
            implementation(project(":openai-client-test"))
            implementation(project(":utils-coroutines-spec"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
