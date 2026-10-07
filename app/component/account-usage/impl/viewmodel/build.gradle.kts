plugins { id("kodex.kmp-viewmodel") }

kotlin {
    sourceSets {
        commonMain.dependencies { api(project(":app-component-account-usage-spec")) }
        commonTest.dependencies { implementation(libs.kotlinx.coroutines.test) }
    }
}
