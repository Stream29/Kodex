plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-context-prefix-agents-md-contract"))
            api(project(":agent-context-prefix-skill-contract"))
            api(project(":utils-shell-client"))
            api(libs.kotlinx.io.core)
        }
    }
}
