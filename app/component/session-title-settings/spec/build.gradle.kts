plugins { id("kodex.kmp-cli") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-shared-settings-contract"))
            api(project(":openai-spec-models"))
            api(libs.kotlinx.coroutines.core)
        }
    }
}
