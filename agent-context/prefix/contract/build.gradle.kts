plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-context-skill-contract"))
            api(libs.kotlinx.datetime)
            api(libs.kotlinx.io.core)
        }
    }
}
