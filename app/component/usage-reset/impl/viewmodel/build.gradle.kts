plugins { id("kodex.kmp-viewmodel") }

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
