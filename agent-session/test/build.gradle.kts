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
                api(project(":agent-storage-in-memory"))
                api(project(":openai-client-test"))
                api(libs.kotlinx.coroutines.core)
            }
        }
        commonMain.dependencies {
            implementation(project(":agent-runtime-composition"))
            implementation(project(":agent-state-core"))
            implementation(project(":agent-state-test"))
            implementation(project(":openai-model-catalog"))
            implementation(project(":openai-models"))
            implementation(project(":utils-coroutines"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":openai-json-codec"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
