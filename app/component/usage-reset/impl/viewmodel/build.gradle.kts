plugins { id("kodex.kmp-viewmodel"); id("kodex.kmp-tests") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-component-usage-reset-spec"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
