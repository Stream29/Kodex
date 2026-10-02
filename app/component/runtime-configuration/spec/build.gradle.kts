plugins { id("kodex.kmp-cli") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":openai-spec-models"))
            api(libs.kotlinx.coroutines.core)
        }
    }
}
