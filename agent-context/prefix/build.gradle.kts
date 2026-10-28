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
                api(project(":agent-context-settings"))
                api(project(":openai-models"))
                api(libs.kotlinx.coroutines.core)
                api(project(":agent-context-agents-md"))
                api(project(":agent-context-skill"))
                api(project(":utils-shell-client"))
                api(libs.kotlinx.io.core)
            }
        }
        commonMain.dependencies {
            api(libs.kotlinx.io.core)
            implementation(project(":agent-context-agents-md"))
            implementation(project(":utils-os-environment"))
            api(project(":agent-context-skill"))
            implementation(project(":agent-context-prompt-dsl"))
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(project(":utils-kotlinx-io-coroutines-system"))
        }
    }
}
