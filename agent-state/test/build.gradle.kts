plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(project(":agent-context-settings"))
                api(project(":mcp-contract"))
                api(libs.kotlinx.coroutines.core)
            }
        }
        commonMain.dependencies {
            implementation(project(":utils-shell-client"))
            implementation(libs.kotlinx.io.core)
        }
    }
}
