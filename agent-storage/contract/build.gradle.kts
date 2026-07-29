plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(project(":agent-storage-clean-models"))
            api(project(":openai-models"))
        }
    }
}
