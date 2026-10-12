plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(project(":agent-context-spec-available-skill"))
                api(libs.kotlinx.io.core)
            }
        }
        commonMain.dependencies {
            api(project(":agent-context-spec-contract"))
            api(project(":utils-kotlinx-io-coroutines-impl"))
            api(libs.kotlinx.coroutines.core)
            implementation(project(":agent-context-spec-available-skill"))
            implementation(project(":agent-context-spec-prompt-dsl"))
        }
        commonTest.dependencies {
            implementation(project(":agent-context-spec-available-skill"))
            implementation(project(":utils-kotlinx-io-coroutines-impl"))
        }
    }
}
