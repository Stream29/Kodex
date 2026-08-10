plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-state-contract"))
            api(project(":agent-storage-clean-models"))
            api(project(":tool-unified-exec-impl"))
            api(libs.kotlinx.coroutines.core)
        }
    }
}
