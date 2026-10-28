plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(project(":agent-session-contract"))
                api(project(":openai-client-test"))
            }
        }
        commonMain.dependencies {
            implementation(project(":agent-state-test"))
            implementation(project(":openai-model-catalog"))
            implementation(project(":openai-models"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
