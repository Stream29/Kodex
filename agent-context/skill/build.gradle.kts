plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(libs.kotlinx.io.core)
            }
        }
        commonMain.dependencies {
            api(project(":agent-context-settings"))
            api(project(":utils-kotlinx-io-coroutines-system"))
            api(libs.kotlinx.coroutines.core)
            implementation(project(":agent-context-prompt-dsl"))
        }
        commonTest.dependencies {
            implementation(project(":utils-kotlinx-io-coroutines-system"))
        }
    }
}
