plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-context-spec-available-skill"))
            api(libs.kotlinx.io.core)
        }
    }
}
