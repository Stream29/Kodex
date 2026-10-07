plugins { id("kodex.kmp-cli") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-storage-spec-contract"))
            api(project(":agent-storage-spec-clean-models"))
            api(libs.kotlinx.coroutines.core)
        }
    }
}
