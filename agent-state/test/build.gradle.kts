plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-context-settings"))
            api(project(":mcp"))
            api(libs.kotlinx.coroutines.core)
            implementation(project(":utils-shell-client"))
            implementation(libs.kotlinx.io.core)
        }
    }
}
