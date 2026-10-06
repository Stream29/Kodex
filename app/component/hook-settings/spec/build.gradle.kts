plugins { id("kodex.kmp-cli") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":hook-spec-notification"))
            api(libs.kotlinx.coroutines.core)
        }
    }
}
