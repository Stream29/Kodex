plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-context-prefix-skill-contract"))
            api(libs.kotlinx.io.core)
        }
    }
}
