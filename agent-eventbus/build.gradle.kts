plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(project(":agent-session-contract"))
                api(project(":openai-models"))
                api(project(":tool-request-user-input"))
                api(libs.kotlinx.coroutines.core)
            }
        }
        commonTest {
            dependencies {
                implementation(project(":agent-session-in-memory"))
                implementation(project(":agent-session-test"))
                implementation(project(":openai-client-test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
