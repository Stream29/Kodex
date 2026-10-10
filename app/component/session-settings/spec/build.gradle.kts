plugins { id("kodex.kmp-cli"); id("kodex.kmp-tests") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":openai-spec-models"))
            api(project(":app-component-path-picker-spec"))
            api(project(":app-component-working-directory-spec"))
            api(project(":app-component-session-rename-spec"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
        }
    }
}
