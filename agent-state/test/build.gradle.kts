plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-context-contract"))
            api(project(":mcp-contract"))
            api(libs.kotlinx.coroutines.core)
            implementation(project(":utils-shell-client"))
            implementation(libs.kotlinx.io.core)
        }
    }
}
