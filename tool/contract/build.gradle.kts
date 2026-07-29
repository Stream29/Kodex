plugins {
    id("codexlite.kmp-host")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-storage-clean-models"))
            api(project(":openai-models"))
        }
    }
}
