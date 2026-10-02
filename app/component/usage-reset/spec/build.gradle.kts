plugins { id("kodex.kmp-cli") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-component-account-usage-spec"))
            api(project(":openai-spec-account-usage"))
            api(libs.kotlinx.coroutines.core)
        }
    }
}
