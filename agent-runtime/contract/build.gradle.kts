plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-state-contract"))
            api(project(":openai-models"))
            api(project(":tool-unified-exec-impl"))
            api(libs.kotlinx.coroutines.core)
        }
    }
}
