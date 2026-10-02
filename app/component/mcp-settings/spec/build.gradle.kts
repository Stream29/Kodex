plugins { id("kodex.kmp-cli") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":mcp-spec-contract"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
        }
    }
}
