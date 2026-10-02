plugins {
    id("kodex.kmp-cli")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-storage-spec-clean-models"))
            api(project(":tool-spec-request-user-input"))
            api(libs.kotlinx.coroutines.core)
        }
    }
}
