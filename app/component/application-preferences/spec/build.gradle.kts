plugins { id("kodex.kmp-cli") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-shared-settings-contract"))
            api(libs.kotlinx.coroutines.core)
        }
    }
}
