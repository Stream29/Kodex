plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(project(":agent-context-agents-md"))
                api(project(":agent-context-spec-available-skill"))
                api(project(":utils-shell-client-spec"))
                api(libs.kotlinx.io.core)
            }
        }
        commonMain.dependencies {
            api(project(":agent-context-spec-contract"))
            api(project(":openai-spec-models"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
            implementation(project(":agent-context-agents-md"))
            implementation(project(":agent-context-skill"))
            implementation(project(":utils-os-environment-spec"))
            api(project(":agent-context-spec-available-skill"))
            implementation(project(":agent-context-spec-prompt-dsl"))
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(project(":utils-kotlinx-io-coroutines-impl"))
        }
    }
}
