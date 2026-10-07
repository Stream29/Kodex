plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-context-spec-contract"))
            api(project(":mcp-spec-contract"))
            api(libs.kotlinx.coroutines.core)
            implementation(project(":utils-shell-client-spec"))
            implementation(libs.kotlinx.io.core)
        }
    }
}
