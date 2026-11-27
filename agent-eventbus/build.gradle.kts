plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(project(":agent-session-contract"))
                api(project(":openai-models"))
                api(project(":tool-request-user-input"))
                api(project(":utils-mq"))
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.serialization.core)
            }
        }
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest {
            dependencies {
                implementation(project(":agent-session-test"))
                implementation(project(":openai-client-test"))
                implementation(project(":openai-json-codec"))
                implementation(project(":tool-await-event"))
                implementation(project(":tool-unified-exec-contract"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
