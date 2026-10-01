plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-context-spec-agents-md"))
            api(project(":agent-context-spec-available-skill"))
            api(project(":utils-shell-client-impl"))
            api(libs.kotlinx.io.core)
        }
    }
}
